package com.gzx.maidmining.client;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 客户端缓存：每个工作站最近一次收到的女仆状态。 */
public final class StationStatusCache {

    private static final Map<BlockPos, List<String>> CACHE = new ConcurrentHashMap<>();

    private StationStatusCache() {
    }

    public static void put(BlockPos pos, List<String> lines) {
        CACHE.put(pos, new ArrayList<>(lines));
    }

    public static List<String> get(BlockPos pos) {
        return CACHE.getOrDefault(pos, List.of());
    }
}
