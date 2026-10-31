package com.gzx.maidmining;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.ai.MineBehavior;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 「采矿」任务。
 */
public class MiningTask implements IMaidTask {

    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(MaidMining.MOD_ID, "mining");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.IRON_PICKAXE);
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return SoundEvents.EMPTY;
    }

    /**
     * 每个女仆会各自调用一次这里，所以可以直接 new 一个带状态的行为实例。
     * 数字 5 是优先级（越小越先执行）。
     */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        MaidMining.debug("[debug] createBrainTasks 被调用！maid={}", maid.blockPosition());
        // 每次重新设定「采矿」任务都会走到这里 → 按她现在的位置刷新工作范围
        MineBehavior.resetFor(maid);

        // 必须返回「可变」列表：TML 的 MaidBrain.registerWorkGoals 会往这个列表里继续 add
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(1, new MineBehavior()));
        return list;
    }

    /** 主手必须是镐子 */
    public static boolean hasPickaxe(EntityMaid maid) {
        return maid.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES);
    }

    @Override
    public boolean isEnable(EntityMaid maid) {
        return hasPickaxe(maid);
    }

    @Override
    public java.util.List<Pair<String, java.util.function.Predicate<EntityMaid>>> getEnableConditionDesc(EntityMaid maid) {
        return java.util.List.of(Pair.of("主手需要拿镐子", MiningTask::hasPickaxe));
    }

    /** 任务设置界面：在女仆的任务列表里点设置就能打开 */
    @Override
    public net.minecraft.world.MenuProvider getTaskConfigGuiProvider(EntityMaid maid) {
        final int entityId = maid.getId();
        return new net.minecraft.world.MenuProvider() {
            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.literal("采矿设置");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int containerId, net.minecraft.world.entity.player.Inventory inv,
                    net.minecraft.world.entity.player.Player player) {
                return new com.gzx.maidmining.menu.MiningConfigMenu(containerId, inv, entityId);
            }
        };
    }

    /** 让她守住工作点，不要到处乱跑 */
    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    @Override
    public String getMaidActionSummary() {
        return "mining";
    }
}
