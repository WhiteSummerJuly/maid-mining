package com.gzx.maidmining.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 找矿模式的「朝矿挖隧道」。
 *
 * <p>矿通常埋在石头里，寻路是走不过去的。这个类每 tick 只做一件事：
 * 朝目标矿的方向挖一格（或在需要时垫高/往下挖），一步步把路挖通。</p>
 */
final class OreTunnel {

    private OreTunnel() {
    }

    /**
     * 朝目标挖一步。
     *
     * @return true 表示这一 tick 已经动了（调用方直接 return）
     */
    static boolean step(ServerLevel level, EntityMaid maid, BlockPos target) {
        BlockPos from = maid.blockPosition();
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();

        // 已经贴脸了 → 交给正常的挖掘逻辑
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && Math.abs(dy) <= 1) {
            return false;
        }

        // ① 目标在上面：先垫高（一列柱子往上爬）
        if (dy >= 2) {
            if (BridgeUp.step(level, maid, target)) {
                return true;
            }
        }

        // ② 主要水平方向
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);

        // 尝试的顺序：主轴 → 副轴 → 斜角
        int[][] dirs = xFirst
                ? new int[][]{{sx, 0}, {0, sz}, {sx, sz}}
                : new int[][]{{0, sz}, {sx, 0}, {sx, sz}};

        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) {
                continue;
            }
            BlockPos ahead = from.offset(d[0], 0, d[1]);
            BlockPos below = ahead.below();

            // 目标在下面：先挖脚下的
            if (dy <= -2) {
                if (diggable(level, maid, below) && diggable(level, maid, ahead)) {
                    MineBehavior.harvest(level, maid, below);
                    return true;
                }
                if (diggable(level, maid, ahead)) {
                    MineBehavior.harvest(level, maid, ahead);
                    return true;
                }
                continue;
            }

            // 平层/向上：把身前两格（脚+头）挖通
            if (diggable(level, maid, ahead)) {
                MineBehavior.harvest(level, maid, ahead);
                return true;
            }
            if (diggable(level, maid, ahead.above())) {
                MineBehavior.harvest(level, maid, ahead.above());   // 清出站人的高度
                return true;
            }
            // 身前一格是空的，但下方没地板 → 补一块走过去
            if (!diggable(level, maid, ahead) && !diggable(level, maid, below)
                    && level.getBlockState(below).isAir()) {
                if (BridgeUp.hasBlocks(maid)) {
                    // 让正常的搭平台逻辑接手
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean diggable(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (MineBehavior.isStairCell(maid, pos) || PlacedTracker.isOurs(pos)) {
            return false;          // 台阶 / 自己放的方块，绝不挖
        }
        BlockState st = level.getBlockState(pos);
        return !st.isAir() && !st.hasBlockEntity() && st.getFluidState().isEmpty()
                && st.getDestroySpeed(level, pos) >= 0;
    }
}
