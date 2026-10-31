package com.gzx.maidmining.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「找矿脉」模式的扫描器。
 *
 * <p>只认矿石：优先高价值的（钻石/远古残骸/绿宝石/金/铁…），
 * 按「价值高 + 距离近」排序。挖到一块会把同类相邻的矿一起加进来（顺着矿脉挖）。</p>
 */
final class OreHunter {

    private OreHunter() {
    }

    /** 公共矿石标签只查一次（每次调用都 create 会一直分配对象，扫描时非常费） */
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> C_ORES =
            net.minecraft.tags.BlockTags.create(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "ores"));

    /** 这个方块算不算矿 */
    static boolean isOre(BlockState state) {
        return state.is(C_ORES) || state.is(net.minecraft.tags.BlockTags.COPPER_ORES)
                || state.is(net.minecraft.tags.BlockTags.IRON_ORES)
                || state.is(net.minecraft.tags.BlockTags.GOLD_ORES)
                || state.is(net.minecraft.tags.BlockTags.DIAMOND_ORES)
                || state.is(net.minecraft.tags.BlockTags.REDSTONE_ORES)
                || state.is(net.minecraft.tags.BlockTags.LAPIS_ORES)
                || state.is(net.minecraft.tags.BlockTags.EMERALD_ORES)
                || state.is(net.minecraft.tags.BlockTags.COAL_ORES);
    }

    /**
     * 挡位过滤：0 = 全部矿，1 = 铁以上，2 = 稀有（钻石/绿宝石/远古残骸/金）。
     */
    static boolean passTier(BlockState state, int tier) {
        if (tier <= 0) {
            return true;
        }
        int v = value(state);
        return tier == 1 ? v >= 50 : v >= 60;
    }

    /** 粗略的"值不值得跑一趟"评分 */
    static int value(BlockState state) {
        String n = state.getBlock().toString().toLowerCase();
        if (n.contains("ancient_debris")) {
            return 100;
        }
        if (n.contains("diamond")) {
            return 90;
        }
        if (n.contains("emerald")) {
            return 80;
        }
        if (n.contains("gold")) {
            return 60;
        }
        if (n.contains("iron")) {
            return 50;
        }
        if (n.contains("lapis")) {
            return 40;
        }
        if (n.contains("redstone")) {
            return 35;
        }
        if (n.contains("copper")) {
            return 20;
        }
        if (n.contains("coal")) {
            return 15;
        }
        return 10;
    }

    /**
     * 在给定范围里扫矿（会限制在区域边界内）。
     *
     * @param radius 水平搜索半径
     */
    static List<BlockPos> scan(ServerLevel level, BlockPos center, int radius, BlockPos[] bounds, int tier) {
        radius = Math.min(radius, 32);
        List<BlockPos> found = new ArrayList<>();
        int yDown = Math.max(level.getMinBuildHeight(), center.getY() - 20);
        int yUp = Math.min(level.getMaxBuildHeight() - 1, center.getY() + 20);

        for (int y = yDown; y <= yUp; y++) {
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    if (bounds != null) {
                        if (x < bounds[0].getX() || x > bounds[1].getX()
                                || z < bounds[0].getZ() || z > bounds[1].getZ()) {
                            continue;
                        }
                    }
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (st.isAir()) {
                        continue;      // 空气直接跳过，省得白算
                    }
                    if (isOre(st) && passTier(st, tier)) {
                        found.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        found.sort(Comparator
                .comparingInt((BlockPos pos) -> -value(level.getBlockState(pos)))
                .thenComparingDouble(pos -> pos.distSqr(center)));
        return found;
    }

    /** 顺着矿脉扩：把这块周围同类型的矿都加进去 */
    static void expandVein(ServerLevel level, BlockPos pos, Set<BlockPos> out, int limit) {
        BlockState base = level.getBlockState(pos);
        String baseName = base.getBlock().toString();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> queue = new ArrayList<>();
        queue.add(pos);
        seen.add(pos);
        while (!queue.isEmpty() && out.size() < limit) {
            BlockPos cur = queue.remove(0);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = cur.offset(dx, dy, dz);
                        if (seen.contains(n)) {
                            continue;
                        }
                        seen.add(n);
                        BlockState st = level.getBlockState(n);
                        if (st.isAir()) {
                            continue;
                        }
                        if (st.getBlock().toString().equals(baseName)) {
                            out.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
    }
}
