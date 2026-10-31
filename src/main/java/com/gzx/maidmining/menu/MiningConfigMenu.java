package com.gzx.maidmining.menu;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.gzx.maidmining.MaidMining;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;

/**
 * 「采矿」任务的设置界面（容器）。
 *
 * <p>数据存在女仆的 persistent data 里，通过 ContainerData 同步到客户端：</p>
 * <ul>
 *   <li>0 = 向下深度（10~120）</li>
 *   <li>1 = 向上高度（0~60）</li>
 *   <li>2 = 是否插火把（0/1）</li>
 * </ul>
 */
public class MiningConfigMenu extends TaskConfigContainer {

    private static final String NBT_DEPTH = "maidmining_depth";
    private static final String NBT_UP = "maidmining_up";
    private static final String NBT_TORCH = "maidmining_torch";

    private final int maidId;
    private final net.minecraft.world.entity.player.Player owner;
    private final ContainerData data;

    public MiningConfigMenu(int containerId, Inventory playerInv, int maidId) {
        super(MaidMining.MINING_CONFIG_MENU.get(), containerId, playerInv, maidId);
        this.maidId = maidId;
        this.owner = playerInv.player;
        this.data = new SimpleContainerData(5);
        addDataSlots(this.data);
    }

    public ContainerData data() {
        return data;
    }

    /** 服务端把女仆身上的真实值推进同步槽，客户端直接用（别让客户端自己去读实体） */
    @Override
    public void broadcastChanges() {
        if (owner != null && !owner.level().isClientSide) {
            var e = owner.level().getEntity(maidId);
            if (e instanceof EntityMaid m) {
                var tag = m.getPersistentData();
                data.set(0, tag.contains(NBT_DEPTH) ? tag.getInt(NBT_DEPTH) : 40);
                data.set(1, Math.max(0, tag.getInt(NBT_UP)));
                data.set(2, tag.contains("maidmining_torch_enabled")
                        ? (tag.getBoolean("maidmining_torch_enabled") ? 1 : 0) : 1);
                data.set(3, tag.getInt("maidmining_mode"));
                data.set(4, tag.getInt("maidmining_ore_tier"));
            }
        }
        super.broadcastChanges();
    }

    /** 客户端工厂用 */
    public static MiningConfigMenu fromNetwork(int id, Inventory inv, net.minecraft.network.RegistryFriendlyByteBuf buf) {
        return new MiningConfigMenu(id, inv, buf.readInt());   // TML 写的是 writeInt（4 字节），不是 varInt
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        var e = player.level().getEntity(maidId);
        if (!(e instanceof EntityMaid maid)) {
            return false;
        }
        var tag = maid.getPersistentData();
        int depth = tag.contains(NBT_DEPTH) ? tag.getInt(NBT_DEPTH) : 40;
        depth = Math.max(0, Math.min(120, depth));
        int up = Math.max(0, Math.min(60, tag.getInt(NBT_UP)));
        boolean torch = !tag.contains("maidmining_torch_enabled") || tag.getBoolean("maidmining_torch_enabled");
        int mode = tag.getInt("maidmining_mode");
        int tier = tag.getInt("maidmining_ore_tier");

        switch (buttonId) {
            case 0 -> depth = Math.min(120, depth + 10);
            case 1 -> depth = Math.max(0, depth - 10);
            case 2 -> up = Math.min(60, up + 10);
            case 3 -> up = Math.max(0, up - 10);
            case 4 -> torch = !torch;
            case 5 -> mode = mode == 0 ? 1 : 0;
            case 6 -> tier = (tier + 1) % 3;
            default -> {
                return false;
            }
        }
        tag.putInt(NBT_DEPTH, depth);
        tag.putInt(NBT_UP, up);
        tag.putBoolean("maidmining_torch_enabled", torch);
        tag.putInt("maidmining_mode", mode);
        tag.putInt("maidmining_ore_tier", tier);
        MaidMining.debug("[debug] 界面设置：深度={} 向上={} 火把={}", depth, up, torch);
        return true;
    }
}
