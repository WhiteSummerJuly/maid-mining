package com.gzx.maidmining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 「烧制模式」：在工作站旁边自己烧矿。
 *
 * <p>对工作站周围一定范围内的熔炉：</p>
 * <ol>
 *   <li>输入槽空 → 从女仆背包（或附近箱子）拿能烧的东西放进去</li>
 *   <li>燃料槽不够 → 补燃料</li>
 *   <li>输出槽有东西 → 取出来放进女仆背包（背包满了就放旁边箱子）</li>
 * </ol>
 */
public final class Smelting {

    /** 熔炉里三个槽：0=输入 1=燃料 2=输出 */
    private static final int SLOT_IN = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_OUT = 2;

    private Smelting() {
    }

    /** 找工作站周围的熔炉 */
    public static List<IItemHandler> findFurnaces(ServerLevel level, BlockPos station, int radius) {
        List<IItemHandler> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = station.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof AbstractFurnaceBlockEntity)) {
                        continue;
                    }
                    IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                    if (h != null) {
                        list.add(h);
                    }
                }
            }
        }
        return list;
    }

    /** 做一轮烧制；返回这一轮干了多少件事 */
    public static int tick(ServerLevel level, EntityMaid maid, BlockPos station, int radius) {
        List<IItemHandler> furnaces = findFurnaces(level, station, radius);
        if (furnaces.isEmpty()) {
            return 0;
        }
        IItemHandler bag = maid.getMaidInv();
        List<IItemHandler> chests = StationStorage.findContainers(level, station);
        int work = 0;

        for (IItemHandler f : furnaces) {
            // ① 取出成品
            ItemStack out = f.getStackInSlot(SLOT_OUT);
            if (!out.isEmpty()) {
                ItemStack taken = f.extractItem(SLOT_OUT, out.getCount(), false);
                ItemStack rest = StationStorage.give(maid, taken);
                if (!rest.isEmpty()) {
                    for (IItemHandler c : chests) {
                        for (int s = 0; s < c.getSlots() && !rest.isEmpty(); s++) {
                            rest = c.insertItem(s, rest, false);
                        }
                    }
                }
                if (!rest.isEmpty()) {
                    net.minecraft.world.level.block.Block.popResource(level, station.above(), rest);
                }
                work++;
            }

            // ② 补燃料
            boolean fuelLow = f.getStackInSlot(SLOT_FUEL).isEmpty()
                    || f.getStackInSlot(SLOT_FUEL).getCount() < 8;
            if (fuelLow) {
                ItemStack fuel = takeSmeltable(level, bag, chests, true);
                if (!fuel.isEmpty()) {
                    ItemStack rest = f.insertItem(SLOT_FUEL, fuel, false);
                    if (!rest.isEmpty()) {
                        StationStorage.give(maid, rest);
                    }
                    work++;
                }
            }

            // ③ 补输入
            if (f.getStackInSlot(SLOT_IN).isEmpty()) {
                ItemStack input = takeSmeltable(level, bag, chests, false);
                if (!input.isEmpty()) {
                    ItemStack rest = f.insertItem(SLOT_IN, input, false);
                    if (!rest.isEmpty()) {
                        StationStorage.give(maid, rest);
                    }
                    work++;
                }
            }
        }
        return work;
    }

    /**
     * 从女仆背包（优先）或箱子里取一份「能烧的」东西。
     *
     * @param fuel true=找燃料；false=找可烧炼的原料
     */
    private static ItemStack takeSmeltable(ServerLevel level, IItemHandler bag,
                                          List<IItemHandler> chests, boolean fuel) {
        ItemStack fromBag = search(level, bag, fuel);
        if (!fromBag.isEmpty()) {
            return fromBag;
        }
        for (IItemHandler c : chests) {
            ItemStack s = search(level, c, fuel);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack search(ServerLevel level, IItemHandler handler, boolean fuel) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (fuel) {
                if (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack)) {
                    return handler.extractItem(slot, Math.min(stack.getCount(), 16), false);
                }
            } else if (isSmeltable(level, stack)) {
                return handler.extractItem(slot, Math.min(stack.getCount(), 16), false);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 这些"石头类"不要烧（烧出来只是平滑石头/石头，白费燃料） */
    private static final java.util.Set<net.minecraft.world.item.Item> NO_SMELT = java.util.Set.of(
            net.minecraft.world.item.Items.COBBLESTONE,
            net.minecraft.world.item.Items.STONE,
            net.minecraft.world.item.Items.GRANITE,
            net.minecraft.world.item.Items.DIORITE,
            net.minecraft.world.item.Items.ANDESITE,
            net.minecraft.world.item.Items.DEEPSLATE,
            net.minecraft.world.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.world.item.Items.TUFF,
            net.minecraft.world.item.Items.CALCITE,
            net.minecraft.world.item.Items.DRIPSTONE_BLOCK,
            net.minecraft.world.item.Items.NETHERRACK,
            net.minecraft.world.item.Items.BASALT,
            net.minecraft.world.item.Items.SMOOTH_BASALT,
            net.minecraft.world.item.Items.BLACKSTONE,
            net.minecraft.world.item.Items.SANDSTONE,
            net.minecraft.world.item.Items.RED_SANDSTONE,
            net.minecraft.world.item.Items.SAND,
            net.minecraft.world.item.Items.RED_SAND
    );

    private static boolean isSmeltable(ServerLevel level, ItemStack stack) {
        if (NO_SMELT.contains(stack.getItem())) {
            return false;   // 石头类、沙子不烧
        }
        if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
            return false;   // 木头不烧成木炭
        }
        try {
            return level.getRecipeManager()
                    .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level)
                    .isPresent();
        } catch (Throwable t) {
            return false;
        }
    }
}
