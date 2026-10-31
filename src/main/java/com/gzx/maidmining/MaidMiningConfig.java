package com.gzx.maidmining;

import net.neoforged.neoforge.common.ModConfigSpec;

/** mod 配置：主要是一个调试日志开关。 */
public final class MaidMiningConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 打开后才会打 [心跳]/[debug] 这类日志 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGS = BUILDER
            .comment("打印调试日志（心跳、扫描、卡死恢复等）。排查问题时打开，平时关掉免得刷屏。")
            .define("debugLogs", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private MaidMiningConfig() {
    }

    public static boolean debugLogs() {
        try {
            return DEBUG_LOGS.get();
        } catch (Exception e) {
            return false;
        }
    }
}
