package com.gzx.maidmining.bauble;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.ai.MineBehavior;
import com.gzx.maidmining.block.MiningStationBlockEntity;
import com.gzx.maidmining.item.MiningBadgeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 工牌饰品的实际逻辑。
 *
 * <p>每 2 秒：</p>
 * <ol>
 *   <li>把自己的状态上报给绑定的工作站（剩多少格 / 在不在挖）</li>
 *   <li>看工作站里有没有放御币：有 → 把御币上的区域自动分配给自己</li>
 * </ol>
 */
public class BadgeBauble implements IMaidBauble {

    @Override
    public void onTick(EntityMaid maid, ItemStack stack) {
        if (maid.level().isClientSide() || maid.tickCount % 40 != 0) {
            return;
        }
        BlockPos st = MiningBadgeItem.stationOf(stack);
        if (st == null) {
            return;
        }
        if (!(maid.level().getBlockEntity(st) instanceof MiningStationBlockEntity be)) {
            return;
        }

        // ---- 停工状态以工作站为准（防止女仆身上残留"停工"导致永远不动）----
        boolean pausedNow = be.paused();
        boolean pausedMaid = maid.getPersistentData().getBoolean("maidmining_paused");
        if (pausedNow != pausedMaid) {
            maid.getPersistentData().putBoolean("maidmining_paused", pausedNow);
            if (pausedNow) {
                maid.getNavigation().stop();
            }
            MaidMining.LOGGER.info("[同步] 女仆 {} 停工状态 → {}", maid.getName().getString(), pausedNow);
        }

        int[] area = maid.getPersistentData().getIntArray("maidmining_area");
        String status;
        boolean combat = maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
        if (maid.getPersistentData().getBoolean("maidmining_paused")) {
            status = combat ? "守卫（停工）" : "停工";
        } else if (combat) {
            status = area.length == 6 ? "守卫中" : "守卫（无区域）";
        } else if (area.length == 6) {
            int remain = MineBehavior.remainingCount(maid);
            status = remain >= 0 ? ("剩 " + remain + " 格") : "未开始";
        } else {
            status = "没有区域";
        }
        be.report(maid.getUUID(), maid.getName().getString(), status);

        ItemStack gohei = be.gohei();
        if (gohei.isEmpty()) {
            return;
        }
        BlockPos a = MiningGohei.corner(gohei, "CornerA");
        BlockPos b = MiningGohei.corner(gohei, "CornerB");
        if (a == null || b == null) {
            return;
        }
        int[] want = com.gzx.maidmining.item.EntityMaidArea.of(a, b);
        if (java.util.Arrays.equals(want, area)) {
            return;
        }
        com.gzx.maidmining.world.StationAssign.assign(maid, st, a, b);
    }
}
