package com.gzx.maidmining.item;

import com.gzx.maidmining.block.MiningStationBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * 矿区工牌（饰品）。
 *
 * <p>用法：</p>
 * <ol>
 *   <li>拿工牌右键「矿业工作站」→ 绑定那个站</li>
 *   <li>把它放进女仆的饰品栏</li>
 * </ol>
 * <p>装备后会把自己的状态上报给工作站；工作站里放了御币，就会自动把区域发给她。</p>
 */
public class MiningBadgeItem extends Item {

    private static final String KEY = "BadgeStation";

    public MiningBadgeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!MiningStationBlock.isStation(level, pos)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            ItemStack stack = context.getItemInHand();
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            tag.putIntArray(KEY, new int[]{pos.getX(), pos.getY(), pos.getZ()});
            stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(
                        Component.literal("工牌已绑定工作站：" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockPos pos = stationOf(stack);
        tooltip.add(Component.literal("右键工作站：绑定").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("装备在女仆饰品栏里生效").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("绑定：" + (pos == null ? "未绑定"
                : pos.getX() + ", " + pos.getY() + ", " + pos.getZ())).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 读工牌上绑定的工作站坐标 */
    public static BlockPos stationOf(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        int[] v = tag.getIntArray(KEY);
        if (v.length == 3) {
            return new BlockPos(v[0], v[1], v[2]);
        }
        return null;
    }
}
