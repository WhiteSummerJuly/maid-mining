package com.gzx.maidmining.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;
import com.gzx.maidmining.MaidMining;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 游戏总线上的客户端事件（只注册一次）。
 *
 * <p>注意：不能在 {@code RegisterMenuScreensEvent} 里 addListener —— 那个事件在资源重载时
 * 会再触发，监听器就会重复注册、每次都往界面里塞一套按钮。</p>
 */
@EventBusSubscriber(modid = MaidMining.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onGuiInit(MaidContainerGuiEvent.Init event) {
        if (!(event.getGui() instanceof MiningConfigScreen screen)) {
            return;
        }
        int top = event.getTopPos();
        int left = event.getLeftPos();
        int bx = MiningConfigScreen.baseAbsX(left);
        int w = 14;
        int h = 11;

        for (int i = 0; i < 5; i++) {
            int ry = MiningConfigScreen.rowAbsY(top, i) + 1;
            final int row = i;
            event.addButton("maidmining_minus_" + i, Button
                    .builder(Component.literal("-"), b -> click(screen, row, false))
                    .bounds(bx + 129, ry, w, h).build());
            event.addButton("maidmining_plus_" + i, Button
                    .builder(Component.literal("+"), b -> click(screen, row, true))
                    .bounds(bx + 152, ry, w, h).build());
        }
    }

    private static void click(MiningConfigScreen screen, int row, boolean plus) {
        var mc = screen.getMinecraft();
        if (mc == null || mc.gameMode == null) {
            return;
        }
        int[] minus = {1, 3, 4, 5, 6};
        int[] plusIds = {0, 2, 4, 5, 6};
        int id = plus ? plusIds[row] : minus[row];
        mc.gameMode.handleInventoryButtonClick(screen.getMenu().containerId, id);
    }
}
