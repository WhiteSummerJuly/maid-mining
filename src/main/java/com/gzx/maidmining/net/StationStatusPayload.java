package com.gzx.maidmining.net;

import com.gzx.maidmining.MaidMining;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 服务端 → 客户端：工作站里每只女仆的状态（名字 + 进度）。 */
public record StationStatusPayload(BlockPos pos, List<String> lines) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StationStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MaidMining.MOD_ID, "station_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StationStatusPayload> CODEC =
            CustomPacketPayload.codec(StationStatusPayload::write, StationStatusPayload::read);

    private static void write(StationStatusPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(payload.pos);
        buf.writeVarInt(payload.lines.size());
        for (String s : payload.lines) {
            // 必须截断！writeUtf 超长会直接抛异常把服务端搞崩
            String t = s.length() > 120 ? s.substring(0, 120) : s;
            buf.writeUtf(t, 128);
        }
    }

    private static StationStatusPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(buf.readUtf(128));
        }
        return new StationStatusPayload(pos, lines);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
