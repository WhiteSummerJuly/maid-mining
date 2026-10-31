package com.gzx.maidmining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作站存储：直接把女仆背包里的东西搬进工作站周围的容器，再把食物搬回给她。
 *
 * <p>关键：{@code EntityMaid.getMaidInv()} 是公开的 {@link ItemStackHandler}，
 * 能直接读写她的背包，不需要反射，也不需要自制背包物品。</p>
 */
public final class StationStorage {

    private StationStorage() {
    }

    /** 搬东西时保留：食物、工具、护甲都不动 */
    public static boolean keepInBag(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (stack.get(DataComponents.FOOD) != null) {
            return true;
        }
        if (stack.getItem() instanceof TieredItem || stack.isDamageableItem()) {
            return true;
        }
        return stack.getItem() instanceof ArmorItem;
    }

    /** 找出工作站周围相邻的容器（3×3×3） */
    public static List<IItemHandler> findContainers(ServerLevel level, BlockPos station) {
        List<IItemHandler> list = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos pos = station.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) {
                        continue;
                    }
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                    if (handler != null) {
                        list.add(handler);
                    }
                }
            }
        }
        return list;
    }

    /** 把女仆背包里「不需要留」的东西搬进容器；返回搬动了几堆 */
    public static int deposit(ServerLevel level, EntityMaid maid, BlockPos station) {
        List<IItemHandler> containers = findContainers(level, station);
        if (containers.isEmpty()) {
            return 0;
        }
        ItemStackHandler inv = maid.getMaidInv();
        int moved = 0;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || keepInBag(stack)) {
                continue;
            }
            int before = stack.getCount();
            ItemStack rest = insertAll(containers, stack.copy());
            if (rest.getCount() != before) {
                moved++;
                inv.setStackInSlot(slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
            }
        }
        return moved;
    }

    private static ItemStack insertAll(List<IItemHandler> handlers, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (IItemHandler h : handlers) {
            for (int slot = 0; slot < h.getSlots() && !rest.isEmpty(); slot++) {
                rest = h.insertItem(slot, rest, false);
            }
            if (rest.isEmpty()) {
                break;
            }
        }
        return rest;
    }

    /** 背包里食物总数 */
    public static int foodCount(EntityMaid maid) {
        ItemStackHandler inv = maid.getMaidInv();
        int total = 0;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 从容器取食物：背包食物少于 low 时补到 up */
    public static int feed(ServerLevel level, EntityMaid maid, BlockPos station, int low, int up) {
        int have = foodCount(maid);
        if (have >= low) {
            return 0;
        }
        int need = up - have;
        List<IItemHandler> containers = findContainers(level, station);
        if (containers.isEmpty()) {
            return 0;
        }
        ItemStackHandler inv = maid.getMaidInv();
        int given = 0;

        for (IItemHandler h : containers) {
            for (int slot = 0; slot < h.getSlots() && given < need; slot++) {
                ItemStack stack = h.getStackInSlot(slot);
                if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) {
                    continue;
                }
                int want = Math.min(stack.getCount(), need - given);
                ItemStack taken = h.extractItem(slot, want, true);
                if (taken.isEmpty()) {
                    continue;
                }
                ItemStack rest = insertIntoBag(inv, taken);
                int actually = taken.getCount() - rest.getCount();
                if (actually <= 0) {
                    continue;
                }
                h.extractItem(slot, actually, false);
                given += actually;
            }
            if (given >= need) {
                break;
            }
        }
        return given;
    }

    private static ItemStack insertIntoBag(ItemStackHandler inv, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < inv.getSlots() && !rest.isEmpty(); slot++) {
            rest = inv.insertItem(slot, rest, false);
        }
        return rest;
    }

    /** 她是否背着车万女仆原版「大背包」 */
    public static boolean hasBag(EntityMaid maid) {
        var type = maid.getMaidBackpackType();
        if (type == null || type.getItem() == null) {
            return false;
        }
        var id = BuiltInRegistries.ITEM.getKey(type.getItem());
        return id != null && id.equals(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "touhou_little_maid", "maid_backpack_big"));
    }

    /** 把一份掉落塞进她背包；返回没塞进去的剩余 */
    public static ItemStack give(EntityMaid maid, ItemStack stack) {
        return insertIntoBag(maid.getMaidInv(), stack);
    }

    /** 背包是否基本满了（没有空位） */
    public static boolean bagNearlyFull(EntityMaid maid) {
        ItemStackHandler inv = maid.getMaidInv();
        int empty = 0;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (inv.getStackInSlot(slot).isEmpty()) {
                empty++;
            }
        }
        return empty <= 1;
    }
}
