package com.gzx.maidmining.bauble;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** 从御币物品上读坐标（工牌用）。 */
final class MiningGohei {

    private MiningGohei() {
    }

    static BlockPos corner(ItemStack stack, String key) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        int[] v = data.copyTag().getIntArray(key);
        if (v.length == 3) {
            return new BlockPos(v[0], v[1], v[2]);
        }
        return null;
    }
}
