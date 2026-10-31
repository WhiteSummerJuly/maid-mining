package com.gzx.maidmining.ai;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 从女仆背包里挑一个"便宜又合适"的方块来用（堵岩浆、垫脚、搭台阶）。
 *
 * <p>优先：圆石 / 泥土 / 石头 / 花岗岩…这些烂大街的；
 * 避免：钻石块、铁块、下界合金、刷怪笼、箱子、工作台之类有价值的。</p>
 */
final class BlockPicking {

    private BlockPicking() {
    }

    private static final String[] PREFER = {
            "cobblestone", "dirt", "stone", "granite", "diorite", "andesite",
            "deepslate", "tuff", "gravel", "netherrack", "blackstone", "basalt", "sand"
    };

    private static final String[] AVOID = {
            "diamond", "emerald", "gold_block", "iron_block", "netherite",
            "ancient_debris", "lapis", "redstone_block", "copper_block",
            "chest", "furnace", "barrel", "hopper", "spawner", "shulker",
            "crafting_table", "obsidian", "crying", "beacon", "anvil"
    };

    /** 返回槽位号，找不到返回 -1 */
    static int findSlot(ItemStackHandler inv) {
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st.isEmpty() || !(st.getItem() instanceof BlockItem bi)) {
                continue;
            }
            if (!bi.getBlock().defaultBlockState().isSolid()) {
                continue;
            }
            String name = bi.getBlock().toString().toLowerCase();
            boolean avoid = false;
            for (String a : AVOID) {
                if (name.contains(a)) {
                    avoid = true;
                    break;
                }
            }
            if (avoid) {
                continue;
            }
            int score = 0;
            for (int k = 0; k < PREFER.length; k++) {
                if (name.contains(PREFER[k])) {
                    score = PREFER.length - k;   // 越靠前分越高
                    break;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
}
