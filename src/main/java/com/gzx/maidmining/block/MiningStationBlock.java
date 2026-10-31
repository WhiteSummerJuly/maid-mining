package com.gzx.maidmining.block;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.menu.StationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * 女仆矿业工作站。
 *
 * <p>右键打开界面（放御币 / 看状态 / 召回）。用采矿御币右键它可以把「家」绑到这里。</p>
 */
public class MiningStationBlock extends Block implements EntityBlock {

    public MiningStationBlock(Properties properties) {
        super(properties);
    }

    public static boolean isStation(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof MiningStationBlock;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MiningStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return null;   // 暂时不需要每 tick 跑
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
            if (!level.isClientSide()) {
                // 老版本放的方块没有方块实体 → 这里补建，否则御币没地方放
                if (level.getBlockEntity(pos) == null) {
                    level.setBlockEntity(new MiningStationBlockEntity(pos, state));
                }
                player.openMenu(new SimpleMenuProvider(
                        (id, inv, p) -> new StationMenu(id, inv, pos),
                        Component.literal("矿业工作站")),
                        buf -> buf.writeBlockPos(pos));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }

    /** 潜行 + 空手右键 = 快速召回（不用开界面） */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() && player.isShiftKeyDown()) {
            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                List<EntityMaid> maids = serverLevel.getEntitiesOfClass(EntityMaid.class,
                        new AABB(pos).inflate(64.0D));
                int moved = 0;
                for (EntityMaid maid : maids) {
                    maid.teleportTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
                    maid.getNavigation().stop();
                    com.gzx.maidmining.ai.MineBehavior.suppressTeleport(maid, 60);
                    moved++;
                }
                player.displayClientMessage(Component.literal(
                        moved == 0 ? "附近没有女仆" : "已召回 " + moved + " 只女仆"), false);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
