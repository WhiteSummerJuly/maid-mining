package com.gzx.maidmining.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 「搭台阶向上」——用背包里的方块给自己铺楼梯，爬回地面。
 *
 * <p>思路参考 promaid 的 BridgeUpBehavior：</p>
 * <ol>
 *   <li>在身前「她这一层」放一块方块</li>
 *   <li>站上去（脚底高 1 格）</li>
 *   <li>重复 → 一步一步搭上去</li>
 * </ol>
 *
 * <p>有防窒息检查：要放的那两格必须是空气。</p>
 */
final class BridgeUp {

    private BridgeUp() {
    }

    private static boolean free3(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 背包里有没有能放的方块 */
    static boolean hasBlocks(EntityMaid maid) {
        return BlockPicking.findSlot(maid.getMaidInv()) >= 0;
    }

    private static int findBlockSlot(ItemStackHandler inv) {
        return BlockPicking.findSlot(inv);
    }

    /**
     * 往上搭一步。返回 true 表示这一 tick 有事可做（请调用方 return）。
     */
    static boolean step(ServerLevel level, EntityMaid maid, BlockPos goal) {
        BlockPos cur = maid.blockPosition();
        if (cur.getY() >= goal.getY()) {
            return false;   // 已经够高了
        }

        // 她脚下就是台阶 → 绝对不许垫高（会把台阶垫高/堵住）
        if (MineBehavior.isStairCell(maid, maid.blockPosition())) {
            return false;
        }

        // 这块区域有台阶 → 不许垫高（走台阶，别垒柱子）；
        // 但在台阶两端例外：最高一级常常比地面矮 1~2 格，她跳不上来，得允许垫一下
        if (MineBehavior.hasStairs(maid) && !MineBehavior.nearStairEnd(maid)) {
            return false;
        }

        ItemStackHandler inv = maid.getMaidInv();
        int slot = findBlockSlot(inv);
        if (slot < 0) {
            return false;   // 没有方块可用
        }
        ItemStack stack = inv.getStackInSlot(slot);
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return false;
        }
        BlockState state = bi.getBlock().defaultBlockState();

        // ---- 优先竖着垫高自己（干净：一列柱子，不是乱七八糟的斜台阶）----
        BlockPos pillarAt = maid.blockPosition();
        // 要垫进台阶里 → 不许
        if (MineBehavior.isStairCell(maid, pillarAt)) {
            return false;
        }
        if (level.getBlockState(pillarAt).isAir()
                && free3(level, pillarAt.above())
                && level.getBlockState(pillarAt.below()).isSolid()) {
            level.setBlock(pillarAt, state, 3);
            PlacedTracker.record(level, pillarAt, state.getBlock(), maid.getUUID());
            stack.shrink(1);
            if (stack.isEmpty()) {
                inv.setStackInSlot(slot, ItemStack.EMPTY);
            }
            maid.setPos(pillarAt.getX() + 0.5D, pillarAt.getY() + 1.0D, pillarAt.getZ() + 0.5D);
            maid.getNavigation().stop();
            maid.fallDistance = 0.0F;
            MaidMining.LOGGER.info("[debug] 垫高一步 {}", pillarAt);
            return true;
        }

        // 朝工作站的水平方向优先，其次任意方向
        int sx = Integer.signum(goal.getX() - cur.getX());
        int sz = Integer.signum(goal.getZ() - cur.getZ());
        int[][] dirs = {{sx, 0}, {0, sz}, {sx, sz}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) {
                continue;
            }
            BlockPos step = cur.offset(d[0], 0, d[1]);
            if (!level.getBlockState(step).isAir()) {
                continue;
            }
            if (!level.getBlockState(step.above()).isAir()) {
                continue;   // 头顶要有空间，不然站不进去
            }
            // 放方块（别放在她身上）并记下来，回站后收掉
            level.setBlock(step, state, 3);
            PlacedTracker.record(level, step, state.getBlock(), maid.getUUID());
            stack.shrink(1);
            if (stack.isEmpty()) {
                inv.setStackInSlot(slot, ItemStack.EMPTY);
            }
            // 站上去
            maid.setPos(step.getX() + 0.5D, step.getY() + 1.0D, step.getZ() + 0.5D);
            maid.getNavigation().stop();
            maid.fallDistance = 0.0F;
            MaidMining.debug("[debug] 搭台阶 {} 站到 y={}", step, step.getY() + 1);
            return true;
        }
        // ---- 兜底：垫高自己（身前被挡的时候用）----
        // 先把人抬高一格，再在原来的位置放方块 → 相当于"柱子上爬"
        BlockPos feet = maid.blockPosition();
        if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                && level.getBlockState(feet.above(2)).isAir()) {
            level.setBlock(feet, state, 3);
            PlacedTracker.record(level, feet, state.getBlock(), maid.getUUID());
            stack.shrink(1);
            if (stack.isEmpty()) {
                inv.setStackInSlot(slot, ItemStack.EMPTY);
            }
            // 方块放在 feet 那一格，站上去脚底应该在 feet+1（原来写成 +2 会浮空掉一格）
            maid.setPos(feet.getX() + 0.5D, feet.getY() + 1.0D, feet.getZ() + 0.5D);
            maid.getNavigation().stop();
            maid.fallDistance = 0.0F;
            MaidMining.debug("[debug] 垫高起步 {}", feet);
            return true;
        }
        return false;
    }
}
