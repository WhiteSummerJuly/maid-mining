package com.gzx.maidmining.block;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Map;
import java.util.UUID;

/**
 * 矿业工作站方块实体。
 *
 * <p>存两样东西：</p>
 * <ul>
 *   <li>1 格储物：放采矿御币（放进来就把区域广播给戴了工牌的女仆）</li>
 *   <li>女仆状态表：哪只女仆在挖、挖到哪儿了、背包满没满</li>
 * </ul>
 */
public class MiningStationBlockEntity extends BlockEntity {

    /** 御币槽 */
    public static final int SLOT_GOHEI = 0;

    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            // 往工作站里放/换御币 = 要开工了 → 自动解除停工
            if (slot == SLOT_GOHEI && !getStackInSlot(SLOT_GOHEI).isEmpty() && paused) {
                paused = false;
                com.gzx.maidmining.MaidMining.LOGGER.info("[同步] 工作站放入御币 → 自动复工");
            }
            setChanged();
        }
    };

    /** 是否整体停工（一站一起停） */
    private boolean paused = false;

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean value) {
        this.paused = value;
        setChanged();
    }

    /** 女仆 UUID → 「名字|状态」（不存档，运行时数据） */
    private final Map<UUID, String> maids = new Object2ObjectOpenHashMap<>();

    public MiningStationBlockEntity(BlockPos pos, BlockState state) {
        super(com.gzx.maidmining.MaidMining.MINING_STATION_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ItemStack gohei() {
        return inventory.getStackInSlot(SLOT_GOHEI);
    }

    public Map<UUID, String> maids() {
        return maids;
    }

    public void report(UUID uuid, String name, String status) {
        maids.put(uuid, name + "|" + status + "|" + (System.currentTimeMillis() / 50L));
    }

    /** 正在挖的女仆状态列表（顺手清掉 20 秒没上报的幽灵） */
    public java.util.List<String> liveStatus() {
        long now = System.currentTimeMillis() / 50L;
        maids.entrySet().removeIf(e -> {
            String[] parts = e.getValue().split("\\|");
            if (parts.length < 3) {
                return true;
            }
            try {
                return now - Long.parseLong(parts[2]) > 400L;
            } catch (NumberFormatException ex) {
                return true;
            }
        });
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String v : maids.values()) {
            String[] parts = v.split("\\|");
            out.add(parts[0] + "|" + parts[1]);
        }
        return out;
    }

    /** 让客户端也能看到方块实体（界面/状态用） */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putBoolean("Paused", paused);
        return tag;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putBoolean("Paused", paused);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        paused = tag.getBoolean("Paused");
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
    }
}
