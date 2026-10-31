package com.gzx.maidmining.client;

import com.gzx.maidmining.MaidMining;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端注册：把我们的界面绑到菜单类型上（仅此一件事）。 */
@EventBusSubscriber(modid = MaidMining.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(MaidMining.MINING_CONFIG_MENU.get(), MiningConfigScreen::new);
        event.register(MaidMining.STATION_MENU.get(), StationScreen::new);
    }
}
