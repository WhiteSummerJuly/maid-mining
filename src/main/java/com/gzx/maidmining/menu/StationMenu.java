package com.gzx.maidmining.menu;

import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.block.MiningStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** 工作站界面：1 格放御币 + 状态显示 + 召回按钮。 */
public class StationMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private final net.minecraft.world.entity.player.Player owner;
    private final ContainerData data;

    public StationMenu(int containerId, Inventory playerInv, BlockPos pos) {
        super(MaidMining.STATION_MENU.get(), containerId);
        this.pos = pos;
        this.owner = playerInv.player;

        IItemHandler handler = null;
        if (playerInv.player.level().getBlockEntity(pos) instanceof MiningStationBlockEntity be) {
            handler = be.inventory();
        }
        if (handler != null) {
            addSlot(new SlotItemHandler(handler, 0, 17, 20));
        } else {
            addSlot(new Slot(new SimpleContainer(1), 0, 17, 20));
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlot(new Slot(playerInv, j + i * 9 + 9, 8 + j * 18, 178 + i * 18));
            }
        }
        for (int j = 0; j < 9; j++) {
            addSlot(new Slot(playerInv, j, 8 + j * 18, 236));
        }

        final BlockPos p = pos;
        final Inventory inv = playerInv;
        this.data = new SimpleContainerData(3);

        addDataSlots(this.data);
    }

    public static StationMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        return new StationMenu(id, inv, buf.readBlockPos());
    }

    public ContainerData data() {
        return data;
    }

    /** 服务端刷新状态槽（客户端别自己去读方块实体） */
    @Override
    public void broadcastChanges() {
        if (owner != null && !owner.level().isClientSide
                && owner.level().getBlockEntity(pos) instanceof MiningStationBlockEntity be) {
            data.set(0, be.maids().size());
            data.set(1, be.gohei().isEmpty() ? 0 : 1);
            data.set(2, be.paused() ? 1 : 0);
        }
        super.broadcastChanges();
    }

    public BlockPos pos() {
        return pos;
    }

    /** 按钮 0 = 召回；按钮 1 = 重新发放工作区 */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId == 1) {
            return resend(player);
        }
        if (buttonId == 2) {
            return togglePause(player);
        }
        if (buttonId != 0) {
            return false;
        }
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return false;
        }
        var list = serverLevel.getEntitiesOfClass(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).inflate(64.0D));
        int moved = 0;
        for (var maid : list) {
            maid.teleportTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
            maid.getNavigation().stop();
            com.gzx.maidmining.ai.MineBehavior.suppressTeleport(maid, 60);
            moved++;
        }
        MaidMining.debug("[debug] 工作站召回：{} 只女仆", moved);
        return true;
    }

    /** 按钮 2：一键停工 / 复工 */
    private boolean togglePause(Player player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof MiningStationBlockEntity be)) {
            return false;
        }
        // 如果站里是"没停工"但有女仆身上还挂着停工标记 → 优先处理成复工
        boolean anyMaidPaused = false;
        for (var maid : level.getEntitiesOfClass(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).inflate(256.0D))) {
            if (wearsBadgeFor(maid, pos)
                    && maid.getPersistentData().getBoolean("maidmining_paused")) {
                anyMaidPaused = true;
                break;
            }
        }
        boolean paused = be.paused() ? false : !anyMaidPaused;
        be.setPaused(paused);
        int n = 0;
        for (var maid : level.getEntitiesOfClass(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).inflate(256.0D))) {
            if (!wearsBadgeFor(maid, pos)) {
                continue;
            }
            maid.getPersistentData().putBoolean("maidmining_paused", paused);
            if (paused) {
                maid.getNavigation().stop();
            }
            com.gzx.maidmining.ai.MineBehavior.forceRefresh(maid);
            n++;
        }
        MaidMining.debug("[debug] {}：{} 只女仆", paused ? "一键停工" : "一键复工", n);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                (paused ? "已停工 " : "已复工 ") + n + " 只女仆"), false);
        return true;
    }

    /** 把工作站里御币上的区域，重新发给所有「装了工牌并绑了这个站」的女仆 */
    private boolean resend(Player player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof MiningStationBlockEntity be)) {
            return false;
        }
        ItemStack gohei = be.gohei();
        BlockPos a = com.gzx.maidmining.item.MiningGoheiItem.cornerOf(gohei, "CornerA");
        BlockPos b = com.gzx.maidmining.item.MiningGoheiItem.cornerOf(gohei, "CornerB");
        if (a == null || b == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "工作站里的御币还没圈好区域"), false);
            return false;
        }
        int n = 0;
        for (var maid : level.getEntitiesOfClass(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).inflate(256.0D))) {
            if (wearsBadgeFor(maid, pos)) {
                com.gzx.maidmining.world.StationAssign.assign(maid, pos, a, b);
                n++;
            }
        }
        MaidMining.debug("[debug] 重新发放工作区给 {} 只女仆", n);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                n == 0 ? "没有女仆装着绑定本站的工牌" : "已重新发放给 " + n + " 只女仆"), false);
        return true;
    }

    private static boolean wearsBadgeFor(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid,
                                         BlockPos station) {
        var inv = maid.getMaidBauble();
        for (int i = 0; i < inv.getSlots(); i++) {
            var st = inv.getStackInSlot(i);
            if (st.isEmpty()) {
                continue;
            }
            if (st.is(MaidMining.MINING_BADGE.get())) {
                BlockPos bd = com.gzx.maidmining.item.MiningBadgeItem.stationOf(st);
                if (station.equals(bd)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index == 0) {
            // 从御币槽 → 玩家背包
            if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 御币槽
            if (!this.moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** 走远了就自动关界面（原来恒 true，跑出几十格还开着） */
    @Override
    public boolean stillValid(Player player) {
        if (!(player.level().getBlockEntity(pos) instanceof MiningStationBlockEntity)) {
            return false;
        }
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }
}
