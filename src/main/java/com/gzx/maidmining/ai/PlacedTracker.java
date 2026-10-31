package com.gzx.maidmining.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「我们自己放的临时方块」（搭台阶、垫高、搭平台、封液体用的）。
 *
 * <p>目的：等女仆回到工作站后把这些方块收掉，别在矿洞里留一堆浮空石头。</p>
 */
final class PlacedTracker {

    private record Info(Block block, UUID maid, long tick) {
    }

    private static final Map<BlockPos, Info> PLACED = new ConcurrentHashMap<>();

    private PlacedTracker() {
    }

    static void record(ServerLevel level, BlockPos pos, Block block, UUID maid) {
        PLACED.put(pos.immutable(), new Info(block, maid, level.getGameTime()));
    }

    /** 清掉这只女仆放的方块（同一存档、同一种方块才拆） */
    /** 这块是不是我们自己放的临时方块（是的话不许挖） */
    static boolean isOurs(BlockPos pos) {
        return PLACED.containsKey(pos);
    }

    static int cleanup(ServerLevel level, UUID maid) {
        int removed = 0;
        long now = level.getGameTime();
        var it = PLACED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            var info = e.getValue();
            boolean mine = info.maid().equals(maid);
            boolean old = now - info.tick() > 6000L;   // 5 分钟没人管也收掉
            if (!mine && !old) {
                continue;
            }
            var current = level.getBlockState(e.getKey());
            if (current.getBlock() == info.block()) {
                level.destroyBlock(e.getKey(), false);
                removed++;
            }
            it.remove();
        }
        return removed;
    }
}
