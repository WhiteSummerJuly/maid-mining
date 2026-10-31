package com.gzx.maidmining.world;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.ai.MineBehavior;
import com.gzx.maidmining.item.EntityMaidArea;
import net.minecraft.core.BlockPos;

/**
 * 「把区域交给一只女仆」的公共逻辑（工牌、工作站按钮、御币都用这里）。
 *
 * <p>会一并：写区域 / 写工作站 / 设家 / 开 home 模式 / **把任务切成采矿** / 清掉停工状态。</p>
 */
public final class StationAssign {

    private StationAssign() {
    }

    public static void assign(EntityMaid maid, BlockPos station, BlockPos a, BlockPos b) {
        maid.getPersistentData().putIntArray(EntityMaidArea.TAG, EntityMaidArea.of(a, b));
        maid.getPersistentData().putIntArray("maidmining_station",
                new int[]{station.getX(), station.getY(), station.getZ()});
        maid.getPersistentData().putBoolean("maidmining_done_reset", true);
        maid.getPersistentData().remove("maidmining_paused");   // 新任务不继承停工状态

        // 任务必须是「采矿」，否则我们的行为根本不会跑
        // 但战斗女仆（是要来守矿区的）不能改，否则她的战斗 AI 就没了
        boolean combat = maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
        if (!combat && (maid.getTask() == null
                || !MaidMining.MINING_TASK.getUid().equals(maid.getTask().getUid()))) {
            maid.setTask(MaidMining.MINING_TASK);
        }

        MineBehavior.suppressTeleport(maid, 60);
        maid.restrictTo(station, 256);
        maid.setHomeModeEnable(true);
        // 分配新任务 = 要干活 → 工作站也解除停工
        if (maid.level().getBlockEntity(station) instanceof com.gzx.maidmining.block.MiningStationBlockEntity be) {
            be.setPaused(false);
        }
        MineBehavior.forceRefresh(maid);
        MaidMining.debug("[debug] 分配区域给 {}：{} → {}（任务已切到采矿）", maid.getUUID(), a, b);
    }
}
