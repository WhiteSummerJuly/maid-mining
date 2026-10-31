package com.gzx.maidmining.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.StationStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/**
 * 让女仆顺手把地上的掉落物捡起来。
 *
 * <p>她挖出来的东西如果因为背包满被弹到地上，这里会去收回来；
 * 顺带也会捡附近的其它掉落物。</p>
 */
final class CollectItems {

    private static final double SEARCH_RADIUS = 8.0D;
    private static final double PICK_SQR = 4.0D;

    private CollectItems() {
    }

    private static boolean inArea(ItemEntity e, net.minecraft.core.BlockPos[] bounds) {
        if (bounds == null) {
            return true;   // 没绑区域：按半径算
        }
        net.minecraft.core.BlockPos p = e.blockPosition();
        // 平面范围放宽 1 格，竖直方向也给点余量
        return p.getX() >= bounds[0].getX() - 1 && p.getX() <= bounds[1].getX() + 1
                && p.getZ() >= bounds[0].getZ() - 1 && p.getZ() <= bounds[1].getZ() + 1
                && p.getY() >= bounds[0].getY() - 48 && p.getY() <= bounds[1].getY() + 48;
    }

    /** 走一步捡东西；正在处理掉落物时返回 true */
    static boolean step(ServerLevel level, EntityMaid maid) {
        // 只捡「工作范围内」的掉落物（没绑区域就退回按半径）
        var bounds = com.gzx.maidmining.item.EntityMaidArea.bounds(maid);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(maid.blockPosition()).inflate(SEARCH_RADIUS),
                e -> e.isAlive() && !e.getItem().isEmpty() && inArea(e, bounds));
        if (items.isEmpty()) {
            return false;
        }
        items.sort(Comparator.comparingDouble(e -> e.distanceToSqr(maid)));
        ItemEntity target = items.get(0);
        double d = target.distanceToSqr(maid);

        if (d > PICK_SQR) {
            // 走过去
            maid.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.6D);
            return true;
        }

        // 到了 → 塞进背包
        ItemStack stack = target.getItem().copy();
        ItemStack rest = StationStorage.give(maid, stack);
        if (rest.isEmpty()) {
            target.discard();
            MaidMining.debug("[debug] 捡起 {}", stack.getItem());
        } else {
            target.setItem(rest);
        }
        return true;
    }
}
