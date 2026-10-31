package com.gzx.maidmining.item;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.component.DataComponents;

import java.util.List;

/**
 * 采矿御币：圈一块地交给女仆去挖。
 *
 * <ul>
 *   <li><b>Shift + 右键方块</b> → 记下起点 A</li>
 *   <li><b>右键方块</b> → 记下对角终点 B（区域成形）</li>
 *   <li><b>右键女仆</b> → 把这块区域交给她，她开始挖</li>
 * </ul>
 */
public class MiningGoheiItem extends Item {

    private static final String KEY_A = "CornerA";
    private static final String KEY_B = "CornerB";

    public MiningGoheiItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();

        // 右键「女仆矿业工作站」= 把工作站绑定到御币上
        if (context.getLevel().getBlockState(pos).getBlock() instanceof com.gzx.maidmining.block.MiningStationBlock) {
            setCorner(stack, "Station", pos);
            player.displayClientMessage(Component.literal("工作站已绑定：" + fmt(pos) + "（右键女仆时一起交给她）"), true);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            setCorner(stack, KEY_A, pos);
            player.displayClientMessage(Component.literal("起点已记录：" + fmt(pos)), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos a = getCorner(stack, KEY_A);
        if (a == null) {
            player.displayClientMessage(Component.literal("先 Shift+右键 设定起点"), true);
            return InteractionResult.SUCCESS;
        }
        setCorner(stack, KEY_B, pos);
        player.displayClientMessage(
                Component.literal("区域已绑定：" + fmt(a) + " → " + fmt(pos)
                        + "（" + (Math.abs(a.getX() - pos.getX()) + 1) + "×"
                        + (Math.abs(a.getY() - pos.getY()) + 1) + "×"
                        + (Math.abs(a.getZ() - pos.getZ()) + 1) + "）"), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(target instanceof EntityMaid maid)) {
            return InteractionResult.PASS;
        }
        BlockPos station = getCorner(stack, "Station");
        if (station != null) {
            maid.getPersistentData().putIntArray("maidmining_station",
                    new int[]{station.getX(), station.getY(), station.getZ()});
            // 把工作站设为她的"家"，范围覆盖整片工作区：
            // 这样车万女仆的「跟随主人」行为不会把她传送到玩家身边。
            BlockPos c1 = getCorner(stack, KEY_A);
            BlockPos c2 = getCorner(stack, KEY_B);
            // 家的半径要盖住「工作站到区域最远角」的全部距离，
            // 否则她一走出范围就会被车万女仆拉回工作站。
            // 直接把家的范围放大到"等于没有距离限制"：
            // 车万女仆只在她「跑出家的范围」时才把她拉回来，范围够大就不会触发。
            int radius = 256;
            com.gzx.maidmining.ai.MineBehavior.suppressTeleport(maid, 60);
            maid.restrictTo(station, radius);
            // home 模式必须开着：TML 的跟随行为在 !isHomeModeEnable() 时才会传送她
            maid.setHomeModeEnable(true);
            player.displayClientMessage(Component.literal(
                    "工作站已设为她的家：" + fmt(station) + "（范围半径 " + radius + " 格，不会被传送）"), true);
        }

        BlockPos a = getCorner(stack, KEY_A);
        BlockPos b = getCorner(stack, KEY_B);
        if (a == null || b == null) {
            player.displayClientMessage(Component.literal("这块地还没圈好（先定起点和终点）"), true);
            return InteractionResult.SUCCESS;
        }
        maid.getPersistentData().putIntArray(EntityMaidArea.TAG, EntityMaidArea.of(a, b));


        com.gzx.maidmining.ai.MineBehavior.forceRefresh(maid);
        MaidMining.debug("[debug] 区域已交给女仆 {}：{} → {}", maid.getUUID(), a, b);
        player.displayClientMessage(Component.literal("这块地交给她了：" + fmt(a) + " → " + fmt(b)), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockPos a = getCorner(stack, KEY_A);
        BlockPos b = getCorner(stack, KEY_B);
        tooltip.add(Component.literal("Shift+右键：定起点").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("右键：定对角终点").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("右键女仆：把这块地交给她挖").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("起点：" + (a == null ? "未设定" : fmt(a))).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("终点：" + (b == null ? "未设定" : fmt(b))).withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------

    /** 读物品上存的整数（没有就用默认值） */
    public static int getInt(ItemStack stack, String key, int def) {
        int v = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt(key);
        return v <= 0 ? def : v;
    }

    /** 写整数到物品 */
    public static void setInt(ItemStack stack, String key, int value) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(key, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static void setCorner(ItemStack stack, String key, BlockPos pos) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = data.copyTag();
        tag.putIntArray(key, new int[]{pos.getX(), pos.getY(), pos.getZ()});
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 给外部读角点用（工作站发区域给女仆时会读） */
    public static BlockPos cornerOf(ItemStack stack, String key) {
        return getCorner(stack, key);
    }

    private static BlockPos getCorner(ItemStack stack, String key) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(key)) {
            return null;
        }
        int[] v = tag.getIntArray(key);
        return v.length == 3 ? new BlockPos(v[0], v[1], v[2]) : null;
    }
}
