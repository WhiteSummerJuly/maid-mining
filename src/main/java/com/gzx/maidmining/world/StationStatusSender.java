package com.gzx.maidmining.world;

import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.block.MiningStationBlockEntity;
import com.gzx.maidmining.menu.StationMenu;
import com.gzx.maidmining.net.StationStatusPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** 每秒把工作站里女仆的状态推给正在看这个界面的玩家。 */
@EventBusSubscriber(modid = MaidMining.MOD_ID)
public class StationStatusSender {

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (var player : server.getPlayerList().getPlayers()) {
            if (!(player.containerMenu instanceof StationMenu menu)) {
                continue;
            }
            if (!(player.level().getBlockEntity(menu.pos()) instanceof MiningStationBlockEntity be)) {
                continue;
            }
            List<String> lines = be.liveStatus();
            lines.sort(String::compareTo);
            PacketDistributor.sendToPlayer(player, new StationStatusPayload(menu.pos(), lines));
        }
    }
}
