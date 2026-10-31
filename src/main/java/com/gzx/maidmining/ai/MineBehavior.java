package com.gzx.maidmining.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import com.gzx.maidmining.item.EntityMaidArea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆采矿行为。
 *
 * <p>设计原则（吃过教训）：</p>
 * <ul>
 *   <li>{@code checkExtraStartConditions} <b>永远返回 true</b>：一旦返回 false，
 *       女仆大脑会把行为彻底丢掉，再也不会挂回来（开背包、坐下、别的行为抢占都会触发）。</li>
 *   <li>所有"干不干活"的判定放在 {@code tick} 里。</li>
 *   <li>进度按女仆 UUID 存静态表：大脑重建会新建行为实例，实例字段会丢。</li>
 *   <li>整片区域算一次可挖清单，之后只挑「不高过她、离她最近」的方块，她自然一路往下。</li>
 * </ul>
 */
public class MineBehavior extends Behavior<EntityMaid> {

    private static final int BREAK_INTERVAL = 3;
    private static final int TORCH_EVERY = 8;
    /** 手臂长度（格²）：约 3 格 */
    private static final double REACH_SQR = 9.5D;
    /** 单个目标最多等多久（tick），超了就跳过 */
    private static final int SKIP_TICKS = 80;

    public static final int DEFAULT_DEPTH = 40;
    public static final int DEFAULT_UP = 0;
    public static final String NBT_DEPTH = "maidmining_depth";
    public static final String NBT_UP = "maidmining_up";

    private static final Map<UUID, Progress> PROGRESS = new ConcurrentHashMap<>();

    /**
     * 是不是被停工了。
     *
     * <p><b>以工作站为准</b>：去读工作站方块实体上的 paused；
     * 只有找不到工作站时，才退回女仆身上的老标记（兼容旧存档）。</p>
     */
    private static boolean isPaused(ServerLevel level, EntityMaid maid) {
        int[] stn = maid.getPersistentData().getIntArray("maidmining_station");
        if (stn.length == 3) {
            BlockPos pos = new BlockPos(stn[0], stn[1], stn[2]);
            if (level.getBlockEntity(pos) instanceof com.gzx.maidmining.block.MiningStationBlockEntity be) {
                return be.paused();
            }
        }
        return maid.getPersistentData().getBoolean("maidmining_paused");
    }

    /** 日志用：停工状态要以工作站为准（读女仆身上旧标记会误导排查） */
    private static boolean isPausedForLog(EntityMaid maid) {
        if (maid.level() instanceof ServerLevel sl) {
            return isPaused(sl, maid);
        }
        return maid.getPersistentData().getBoolean("maidmining_paused");
    }

    /** 为什么没在干活？每 15 秒打一次，方便排查 */
    private static void whyIdle(EntityMaid maid, Progress p, String reason) {
        if (p.heartbeat % 300 == 0) {
            MaidMining.LOGGER.info("[诊断] 停手原因：{}（pos={} 剩={} 状态={} 完工={} 停工={}）",
                    reason, maid.blockPosition(), p.remaining.size(), p.state, p.done,
                    isPausedForLog(maid));
        }
    }


    /** 被认领的方块 → 认领者（女仆 UUID）+ 认领时刻。防止多只女仆挖同一格。 */
    private static final Map<BlockPos, long[]> CLAIMS = new ConcurrentHashMap<>();

    /** 还剩多少格没挖（给界面/饰品读状态用）；-1 = 还没开始 */
    public static int remainingCount(EntityMaid maid) {
        Progress p = PROGRESS.get(maid.getUUID());
        if (p == null) {
            return -1;
        }
        return p.remaining.size();
    }

    private static boolean claimedByOther(BlockPos pos, EntityMaid maid, long now) {
        long[] c = CLAIMS.get(pos);
        if (c == null) {
            return false;
        }
        if (now - c[1] > 100L) {          // 5 秒没动过，作废
            CLAIMS.remove(pos);
            return false;
        }
        return c[0] != maid.getId();
    }

    private static void claim(BlockPos pos, EntityMaid maid, long now) {
        CLAIMS.put(pos, new long[]{maid.getId(), now});
    }

    private static final class Progress {
        String areaSig = "";
        Set<BlockPos> remaining = new HashSet<>();
        BlockPos target = null;
        int waitTicks = 0;
        int cooldown = 0;
        int mined = 0;
        int heartbeat = 0;
        net.minecraft.core.BlockPos lastPos = null;
        java.util.Set<BlockPos> stairCells = new java.util.HashSet<>();
        /** 台阶的有序路径（从楼梯口到底部），用来一级一级喂给寻路 */
        java.util.List<BlockPos> stairPath = new java.util.ArrayList<>();
        /** 当前走到第几级 */
        int stairIndex = -1;
        /** 在同一个路点上耗了多久（走不到就交接） */
        int stairWaitTicks = 0;
        /** 台阶位置但原地是会掉落的方块 → 需要挖掉后用圆石补一块 */
        final java.util.Set<BlockPos> stairFill = new java.util.HashSet<>();
        int oreEmptyTicks = 0;
        int returnTicks = 0;
        /** 清理模式：当前该挖的那一层（缓存） */
        int topLayer = Integer.MIN_VALUE;
        boolean topLayerDirty = true;
        /** 全部完成（挖完 + 烧完）后停工 */
        boolean done = false;
        /** 找矿模式：挖掉一块后要求立刻重扫 */
        boolean oreRescan = false;
        /** 找矿模式下没矿可挖时，自己溜达的目标点 */
        BlockPos wanderPos = null;
        int wanderTicks = 0;
        /** 挖矿进度卡死检测 */
        int lastMined = 0;
        int idleTicks = 0;
        /** 连续多少轮熔炉没事可做 */
        int smeltIdle = 0;
        /** 被跳过（走不到/挖不动）的目标 → 解禁时刻(tick) */
        final Map<BlockPos, Long> skipUntil = new java.util.HashMap<>();
        /** 连续几次塞不进背包（判定为满） */
        int fullCount = 0;
        /** 0=挖矿 1=回站 2=到站等待 */
        int state = 0;
        int stationWait = 0;
        /** 站旁自动卸货的计时（和"在站里等"分开，免得互相干扰） */
        int depositWait = 0;
    }

    public MineBehavior() {
        super(Map.of());
    }

    private static Progress progress(EntityMaid maid) {
        return PROGRESS.computeIfAbsent(maid.getUUID(), k -> new Progress());
    }

    /** 玩家绑了新区域：清进度，下一 tick 重建清单 */
    public static void forceRefresh(EntityMaid maid) {
        Progress p = PROGRESS.get(maid.getUUID());
        if (p != null) {
            p.areaSig = "";
            p.remaining.clear();
            p.target = null;
        }
        MaidMining.debug("[debug] 已按新区域刷新");
    }

    public static void resetFor(EntityMaid maid) {
        // 保留进度：大脑重建不该让她从头再来
    }

    /** 我们自己主动传送了她（召回/爬梯）时调用，让防传送守卫别把她拉回来 */
    public static void suppressTeleport(EntityMaid maid, int ticks) {
        // 目前没有"防传送守卫"在读它，保留空实现只为不破坏调用点
        MaidMining.debug("[debug] 抑制传送 {} tick", ticks);
    }

    /** 某只女仆没了 → 清掉她的进度和方块认领 */
    public static void forgetMaid(java.util.UUID uuid, int entityId) {
        PROGRESS.remove(uuid);
        CLAIMS.entrySet().removeIf(e -> e.getValue()[0] == entityId);
    }

    public static void clearAll() {
        PROGRESS.clear();
        CLAIMS.clear();
    }

    // ------------------------------------------------------------------

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        // 需要走动，不锁寻路
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        Progress p = progress(maid);
        p.heartbeat++;

        // ===== 最优先：让车万女仆的「日程位置」跟着她走 =====
        // 必须放在所有 return 之前（停工、没区域、没镐……都要更新），
        // 否则 TML 每 40 tick 会判定"离家太远"，把她传送回旧工作区。
        if (p.heartbeat % 40 == 0) {
            int[] st = maid.getPersistentData().getIntArray("maidmining_station");
            if (st.length == 3) {
                // 家 = 工作站（不是她脚下！）
                // 之前把她脚下设成"家"，车万女仆会认为她"已经在家"，
                // 任何往外走都被"待在家里"的逻辑拽住 → 走不到远处的矿区和回不了站。
                BlockPos station = new BlockPos(st[0], st[1], st[2]);
                var sp = maid.getSchedulePos();
                if (sp != null) {
                    if (!station.equals(sp.getWorkPos())) {
                        sp.setWorkPos(station);
                    }
                    if (!station.equals(sp.getIdlePos())) {
                        sp.setIdlePos(station);
                    }
                    if (!station.equals(sp.getSleepPos())) {
                        sp.setSleepPos(station);
                    }
                    if (!sp.isConfigured()) {
                        sp.setConfigured(true);
                    }
                }
                // 范围每 2 秒重新声明一次，免得被 TML 用默认 12 格覆盖
                maid.restrictTo(station, 256);
            }
            if (!maid.isHomeModeEnable()) {
                maid.setHomeModeEnable(true);
            }
        }

        // 进度卡死计数（任何分支 return 都不漏）
        if (p.mined != p.lastMined) {
            p.lastMined = p.mined;
            p.idleTicks = 0;
        } else {
            p.idleTicks++;
        }

        // 重新发放了工作区 → 清掉完工/烧矿状态
        if (maid.getPersistentData().getBoolean("maidmining_done_reset")) {
            maid.getPersistentData().remove("maidmining_done_reset");
            p.done = false;
            p.smeltIdle = 0;
            p.state = 0;
            p.target = null;
            p.waitTicks = 0;
            MaidMining.LOGGER.info("[任务] 收到新任务：清掉烧矿/完工状态，重新开工");
        }

        // 停工（以工作站为准）
        if (isPaused(level, maid)) {
            maid.getNavigation().stop();
            p.target = null;
            whyIdle(maid, p, "被「一键停工」停着");
            return;
        }

        // 已完工
        if (p.done) {
            maid.getNavigation().stop();
            whyIdle(maid, p, "区域已挖完（停工状态）");
            return;
        }


        // 坐着不干活 → 站起来
        if (maid.isMaidInSittingPose()) {
            maid.setInSittingPose(false);
        }

        // 灭火 + 补氧
        if (maid.tickCount % 20 == 0) {
            if (maid.isOnFire()) {
                maid.clearFire();
            }
            if (maid.getAirSupply() < maid.getMaxAirSupply()) {
                maid.setAirSupply(maid.getMaxAirSupply());
            }
        }

        // 必须拿镐
        if (!com.gzx.maidmining.MiningTask.hasPickaxe(maid)) {
            whyIdle(maid, p, "主手没拿镐子");
            return;
        }

        BlockPos[] bnd = EntityMaidArea.bounds(maid);
        if (bnd == null) {
            p.areaSig = "";
            p.remaining.clear();
            p.target = null;
            whyIdle(maid, p, "没有分配区域");
            return;
        }

        // ===== 站在工作站旁边 → 自动卸货 / 补给（放最前面，挖完了也要能存）=====
        {
            int[] stn0 = maid.getPersistentData().getIntArray("maidmining_station");
            if (stn0.length == 3) {
                BlockPos stnPos = new BlockPos(stn0[0], stn0[1], stn0[2]);
                if (maid.blockPosition().distSqr(stnPos) <= 16.0D) {
                    if (p.depositWait++ >= 40) {
                        p.depositWait = 0;
                        int moved = com.gzx.maidmining.StationStorage.deposit(level, maid, stnPos);
                        int fed = com.gzx.maidmining.StationStorage.feed(level, maid, stnPos, 32, 64);
                        if (moved > 0 || fed > 0) {
                            MaidMining.LOGGER.info("[卸货] 在站旁：搬走 {} 堆，补给食物 {} 个", moved, fed);
                        }
                    }
                } else {
                    p.depositWait = 0;
                }
            }
        }

        // ===== 找矿模式：定期扫矿 =====
        boolean oreMode = maid.getPersistentData().getInt("maidmining_mode") == 1;

        // 找矿模式：不在区域里就先走进去（否则只扫脚边，永远扫不到区域里的矿）
        if (oreMode && !insideArea(maid.blockPosition(), bnd)) {
            BlockPos center = new BlockPos(
                    (Math.min(bnd[0].getX(), bnd[1].getX())
                            + Math.max(bnd[0].getX(), bnd[1].getX())) / 2,
                    maid.blockPosition().getY(),
                    (Math.min(bnd[0].getZ(), bnd[1].getZ())
                            + Math.max(bnd[0].getZ(), bnd[1].getZ())) / 2);
            // 每 10 tick 才下一次指令：每 tick 重置寻路会让她在原地抖
            if (p.heartbeat % 10 == 0 || maid.getNavigation().isDone()) {
                maid.getNavigation().moveTo(center.getX() + 0.5D, center.getY() + 1.0D,
                        center.getZ() + 0.5D, 0.6D);
            }
            p.waitTicks++;
            if (BridgeUp.step(level, maid, center)) {
                return;
            }
            whyIdle(maid, p, "不在工作区里 → 走过去");
            return;
        }

        if (oreMode) {
            boolean needScan = p.remaining.size() < 4
                    ? maid.tickCount % 20 == 0
                    : maid.tickCount % 100 == 0;
            if (needScan || p.remaining.isEmpty() || p.oreRescan) {
                p.oreRescan = false;
                // 只清空气；开路方块不是矿，但它是我们自己加的，不能清
                p.remaining.removeIf(pos -> level.getBlockState(pos).isAir());
                BlockPos[] padded = new BlockPos[]{
                        bnd[0].offset(-8, 0, -8), bnd[1].offset(8, 0, 8)};
                int tier = maid.getPersistentData().getInt("maidmining_ore_tier");
                int direct = 0, needOpen = 0;
                BlockPos scanCenter = insideArea(maid.blockPosition(), bnd)
                        ? maid.blockPosition()
                        : new BlockPos((Math.min(bnd[0].getX(), bnd[1].getX())
                                + Math.max(bnd[0].getX(), bnd[1].getX())) / 2,
                                maid.blockPosition().getY(),
                                (Math.min(bnd[0].getZ(), bnd[1].getZ())
                                        + Math.max(bnd[0].getZ(), bnd[1].getZ())) / 2);
                for (BlockPos pos : OreHunter.scan(level, scanCenter, 32, padded, tier)) {
                    if (standPos(level, pos, maid.blockPosition()) != null) {
                        p.remaining.add(pos);
                        direct++;
                    } else {
                        BlockPos open = approachBlock(level, maid, pos, p.remaining);
                        if (open != null) {
                            p.remaining.add(open);
                            needOpen++;
                        }
                    }
                }
                MaidMining.LOGGER.info("[找矿] 可直达 {} 处 / 需开路 {} 处 / 待挖共 {} 格",
                        direct, needOpen, p.remaining.size());
            }
            String oreSig = "ore|" + bnd[0] + "|" + bnd[1];
            if (!oreSig.equals(p.areaSig)) {
                p.remaining.clear();               // 换了区域 → 旧矿单作废
                p.topLayerDirty = true;
            }
            p.areaSig = oreSig;
        } else {
            int[] gi = groupIndex(level, maid, bnd);
            String sig = bnd[0] + "|" + bnd[1] + "|" + gi[0] + "/" + gi[1];
            if (!sig.equals(p.areaSig)) {
                buildList(level, maid, p, bnd, sig, gi[0], gi[1]);
                p.done = false;
                p.smeltIdle = 0;
                p.state = 0;
            }
        }

        // 每 10 秒清一次"已经是空气"的待挖条目（别的女仆挖掉的）
        if (!oreMode && !p.remaining.isEmpty() && maid.tickCount % 200 == 0) {
            int b4 = p.remaining.size();
            p.remaining.removeIf(pos -> level.getBlockState(pos).isAir());
            if (p.remaining.size() != b4) {
                p.topLayerDirty = true;
                MaidMining.debug("[debug] 清掉 {} 个已被挖掉的空格", b4 - p.remaining.size());
            }
        }

        // ===== 捡地上的掉落物 =====
        if (maid.tickCount % 20 == 0) {
            if (CollectItems.step(level, maid)) {
                return;
            }
        }

        // ===== 回站 / 卸货 / 烧矿 状态机 =====
        if (p.state > 0) {
            int[] st = maid.getPersistentData().getIntArray("maidmining_station");
            if (st.length != 3) {
                p.state = 0;
            } else {
                BlockPos station = new BlockPos(st[0], st[1], st[2]);
                if (p.state == 1) {
                    p.returnTicks++;
                    if (p.returnTicks > 1200) {          // 一分钟回不去 → 传送
                        p.returnTicks = 0;
                        p.state = 0;
                        p.fullCount = 0;
                        maid.teleportTo(station.getX() + 0.5D, station.getY() + 1.0D, station.getZ() + 0.5D);
                        MaidMining.LOGGER.info("[回站] 一分钟回不去 → 强制传送到工作站");
                        return;
                    }
                    if (maid.blockPosition().distSqr(station) <= 9.0D) {
                        p.returnTicks = 0;
                        p.state = 2;
                        p.stationWait = 0;
                        maid.getNavigation().stop();
                    } else {
                        p.waitTicks++;
                        if (p.heartbeat % 40 == 0) {
                            BlockPos wp = (p.stairIndex >= 0 && p.stairIndex < p.stairPath.size())
                                    ? p.stairPath.get(p.stairIndex) : null;
                            MaidMining.LOGGER.info("[回站] 位置={} 站={} 距站={} 台阶索引={}/{} 路点={} 路点距={} 寻路={}",
                                    maid.blockPosition(), station,
                                    String.format("%.1f", Math.sqrt(maid.blockPosition().distSqr(station))),
                                    p.stairIndex, p.stairPath.size(), wp,
                                    wp == null ? "-"
                                            : String.format("%.1f", Math.sqrt(wp.distSqr(maid.blockPosition()))),
                                    maid.getNavigation().isInProgress());
                        }
                        if (stairNavigate(level, maid, p, station)) {
                            return;
                        }
                        // 台阶不管了 → 交给原版寻路（同一时刻只有一条指令生效）
                        if (p.heartbeat % 10 == 0 || maid.getNavigation().isDone()) {
                            maid.getNavigation().moveTo(station.getX() + 0.5, station.getY(),
                                    station.getZ() + 0.5, 0.7D);
                        }
                        if (BridgeUp.step(level, maid, station)) {
                            return;
                        }
                        if (p.waitTicks > 600) {
                            p.state = 0;
                            p.waitTicks = 0;
                            MaidMining.LOGGER.info("[回站] 到不了工作站，放弃往返");
                        }
                        return;
                    }
                }
                if (p.state == 2) {
                    p.stationWait++;
                    if (p.stationWait == 1) {
                        int moved = com.gzx.maidmining.StationStorage.deposit(level, maid, station);
                        int fed = com.gzx.maidmining.StationStorage.feed(level, maid, station, 32, 64);
                        MaidMining.LOGGER.info("[到站] 存进箱子 {} 堆，补给食物 {} 个", moved, fed);
                    }
                    if (p.stationWait > 40) {
                        int cleaned = PlacedTracker.cleanup(level, maid.getUUID());
                        if (cleaned > 0) {
                            MaidMining.LOGGER.info("[清理] 收掉自己放的 {} 块临时方块", cleaned);
                        }
                        p.state = 0;
                        p.waitTicks = 0;
                        p.fullCount = 0;
                    }
                    return;
                }
            }
        }

        // ===== 休息间隔 =====
        if (p.cooldown > 0) {
            p.cooldown--;
            return;
        }

        // ===== 找矿模式：没扫到矿 → 在区域里找（别立刻当成"挖完了"）=====
        if (oreMode && p.remaining.isEmpty()) {
            p.oreEmptyTicks++;
            if (p.oreEmptyTicks < 600) {              // 30 秒内继续找
                p.wanderTicks++;
                if (p.wanderPos == null || p.wanderTicks > 60
                        || maid.blockPosition().distSqr(p.wanderPos) < 9.0D) {
                    p.wanderTicks = 0;
                    p.wanderPos = randomSpotIn(bnd);
                }
                if (p.wanderPos != null
                        && (p.heartbeat % 10 == 0 || maid.getNavigation().isDone())) {
                    maid.getNavigation().moveTo(p.wanderPos.getX() + 0.5D,
                            p.wanderPos.getY() + 1.0D, p.wanderPos.getZ() + 0.5D, 0.55D);
                }
                whyIdle(maid, p, "附近没扫到矿 → 在区域里找");
                return;
            }
        } else {
            p.oreEmptyTicks = 0;
        }

        // ===== 区域挖完 → 回站烧矿 → 完工 =====
        if (p.remaining.isEmpty()) {
            p.target = null;
            int[] stn0 = maid.getPersistentData().getIntArray("maidmining_station");
            if (stn0.length == 3) {
                BlockPos station = new BlockPos(stn0[0], stn0[1], stn0[2]);
                if (maid.blockPosition().distSqr(station) > 16.0D) {
                    p.waitTicks++;
                    if (stairNavigate(level, maid, p, station)) {
                        return;
                    }
                    if (p.heartbeat % 10 == 0 || maid.getNavigation().isDone()) {
                        maid.getNavigation().moveTo(station.getX() + 0.5, station.getY(),
                                station.getZ() + 0.5, 0.6D);
                    }
                    if (BridgeUp.step(level, maid, station)) {
                        return;
                    }
                } else if (p.heartbeat % 40 == 0) {
                    int work = com.gzx.maidmining.Smelting.tick(level, maid, station, 4);
                    if (work > 0) {
                        p.smeltIdle = 0;
                    } else if (++p.smeltIdle >= 8) {
                        p.done = true;
                        p.smeltIdle = 0;
                        maid.getNavigation().stop();
                        MaidMining.LOGGER.info("[完工] 区域挖空、熔炼结束，停工");
                    }
                }
            } else {
                p.done = true;
            }
            return;
        }

        // 顺手把缺的台阶补上（原位置是沙砾时）
        if (maid.tickCount % 10 == 0 && fillStairStep(level, maid, p)) {
            p.cooldown = 2;
            return;
        }

        // ===== 挑目标 =====
        BlockPos from = maid.blockPosition();
        if (p.target == null || !p.remaining.contains(p.target)) {
            p.target = pickTarget(from, p, maid);
            if (p.target == null) {
                // 附近没得挖 → 走向区域中心（别站着不动）
                p.wanderTicks++;
                if (p.wanderPos == null || p.wanderTicks > 100
                        || maid.blockPosition().distSqr(p.wanderPos) < 4.0D) {
                    p.wanderTicks = 0;
                    p.wanderPos = randomSpotIn(bnd);
                    if (p.wanderPos != null) {
                        maid.getNavigation().moveTo(p.wanderPos.getX() + 0.5D, p.wanderPos.getY(),
                                p.wanderPos.getZ() + 0.5D, 0.6D);
                    }
                }
                whyIdle(maid, p, "附近没有可挖方块");
                return;
            }
            p.waitTicks = 0;
        }

        BlockPos best = p.target;
        double dist = best.distSqr(from);

        // 站到侧面再挖（脚下有平台）；没有平台就自己搭一块
        BlockPos stand = standPos(level, best, from);
        boolean atStand = stand == null || stand.distSqr(from) <= 6.25D || dist <= 4.0D;
        if (!atStand) {
            if (p.waitTicks % 10 == 0) {
                maid.getNavigation().moveTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0.7D);
            }
            p.waitTicks++;
            // 找矿模式：站位走不过去 → 直接朝矿挖隧道
            if (oreMode && p.waitTicks > 20 && p.waitTicks % 5 == 0) {
                if (OreTunnel.step(level, maid, best)) {
                    p.cooldown = 4;
                    p.waitTicks = 0;
                    return;
                }
            }
            if (p.waitTicks > SKIP_TICKS) {
                p.remaining.remove(best);
                p.skipUntil.put(best, System.currentTimeMillis() / 50L + 1200L);
                p.target = null;
                p.waitTicks = 0;
                return;
            }
            return;
        }

        if (dist <= REACH_SQR) {
            // 先确认那格还是方块（可能已被别的女仆挖掉），再动手
            BlockState before = level.getBlockState(best);
            if (before.isAir() || !isMineable(before)) {
                p.remaining.remove(best);
                p.topLayerDirty = true;
                p.target = null;
                return;                                  // 别对着空气挥镐
            }

            p.cooldown = BREAK_INTERVAL;
            p.remaining.remove(best);
            p.topLayerDirty = true;
            p.target = null;
            if (dist <= 4.0D) {
                maid.getNavigation().stop();
            }
            maid.swing(InteractionHand.MAIN_HAND);
            int result = mineOne(level, maid, best);
            if (result == 2) {
                p.fullCount++;
                if (p.fullCount >= 5) {
                    p.state = 1;
                    p.fullCount = 0;
                    MaidMining.LOGGER.info("[背包] 塞不进去了 → 回工作站");
                }
            } else if (result == 1) {
                p.fullCount = 0;
            }
            if (result != 0) {
                sealFluids(level, maid, best);
                if (oreMode && OreHunter.isOre(before)) {
                    OreHunter.expandVein(level, best, p.remaining, 64);
                    p.oreRescan = true;
                }
                p.mined++;
                if (p.mined % TORCH_EVERY == 0) {
                    placeWallTorch(level, best, p.remaining, bnd);
                }
            }
            return;
        }

        // 找矿模式够不到 → 朝矿挖隧道（矿埋在石头里，寻路是走不过去的）
        if (oreMode) {
            p.waitTicks++;
            if (p.waitTicks % 5 == 0 && OreTunnel.step(level, maid, best)) {
                p.cooldown = 4;
                return;
            }
            if (p.waitTicks > SKIP_TICKS * 3) {
                p.skipUntil.put(best, System.currentTimeMillis() / 50L + 1200L);
                p.remaining.remove(best);
                p.target = null;
                p.waitTicks = 0;
            }
            return;
        }

        // 清理模式够不到 → 5 格内先挖一块开路
        if (p.waitTicks > 20 && p.waitTicks % 20 == 0) {
            BlockPos nearby = null;
            double nd = Double.MAX_VALUE;
            long now = gameTime;
            for (BlockPos pos : p.remaining) {
                double d2 = pos.distSqr(from);
                if (d2 > 25.0D || claimedByOther(pos, maid, now)) {
                    continue;
                }
                if (d2 < nd) {
                    nd = d2;
                    nearby = pos;
                }
            }
            if (nearby != null) {
                p.target = nearby;
                p.waitTicks = 0;
                return;
            }
        }

        p.waitTicks++;
        if (p.heartbeat % 10 == 0 || maid.getNavigation().isDone()) {
            maid.getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 0.5D);
        }
        if (p.waitTicks > SKIP_TICKS) {
            p.remaining.remove(best);
            p.target = null;
            p.waitTicks = 0;
        }
    }

    // ------------------------------------------------------------------

    /**
     * 这一格能不能当台阶：
     * 矿物（要挖走）、沙砾/沙子/混凝土粉末（会掉落，塌了就断）都不行。
     */
    private static boolean isStableStep(BlockState st) {
        if (st.isAir() || st.hasBlockEntity() || !st.getFluidState().isEmpty()) {
            return false;
        }
        if (st.is(Blocks.GRAVEL) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND)
                || st.is(Blocks.SUSPICIOUS_SAND) || st.is(Blocks.SUSPICIOUS_GRAVEL)
                || st.is(Blocks.WHITE_CONCRETE_POWDER) || st.is(Blocks.ORANGE_CONCRETE_POWDER)
                || st.is(Blocks.MAGENTA_CONCRETE_POWDER) || st.is(Blocks.LIGHT_BLUE_CONCRETE_POWDER)
                || st.is(Blocks.YELLOW_CONCRETE_POWDER) || st.is(Blocks.LIME_CONCRETE_POWDER)
                || st.is(Blocks.PINK_CONCRETE_POWDER) || st.is(Blocks.GRAY_CONCRETE_POWDER)
                || st.is(Blocks.LIGHT_GRAY_CONCRETE_POWDER) || st.is(Blocks.CYAN_CONCRETE_POWDER)
                || st.is(Blocks.PURPLE_CONCRETE_POWDER) || st.is(Blocks.BLUE_CONCRETE_POWDER)
                || st.is(Blocks.BROWN_CONCRETE_POWDER) || st.is(Blocks.GREEN_CONCRETE_POWDER)
                || st.is(Blocks.RED_CONCRETE_POWDER) || st.is(Blocks.BLACK_CONCRETE_POWDER)
                || st.is(net.minecraft.tags.BlockTags.SAND)) {
            return false;
        }
        return !OreHunter.isOre(st);
    }

    /** 每跑最多几级 */
    private static final int STAIR_RUN_MAX = 8;

    /** stairKeepSet 顺带把有序路点放这里（避免改一堆签名） */
    private static final ThreadLocal<java.util.List<BlockPos>> STAIR_PATH_HOLDER
            = ThreadLocal.withInitial(java.util.ArrayList::new);

    /**
     * 算出「双跑回转楼梯」要保留的方块。
     *
     * <p>形状：两条跑道各 2 格宽、紧贴（共 4 格宽），贴着离工作站最近的那面墙；
     * 从靠工作站那一头出发，一跑降 1 格/格，跑到头放平台、掉头换跑道，一直折到底。</p>
     *
     * <p><b>每跑几级不是写死的</b>：从 8 往下试，取第一个"不叠方块、且上下都能走通"的
     * （在本地用同样算法验证过：同列不会出现相邻两层、任意两块之间落差为 1 可通行）。</p>
     */
    private static java.util.Set<BlockPos> stairKeepSet(EntityMaid maid, BlockPos[] bnd, int bottomY) {
        java.util.Set<BlockPos> keep = new java.util.HashSet<>();
        int minX = Math.min(bnd[0].getX(), bnd[1].getX());
        int maxX = Math.max(bnd[0].getX(), bnd[1].getX());
        int minZ = Math.min(bnd[0].getZ(), bnd[1].getZ());
        int maxZ = Math.max(bnd[0].getZ(), bnd[1].getZ());

        int[] st = maid.getPersistentData().getIntArray("maidmining_station");
        int sx = (minX + maxX) / 2;
        int sz = (minZ + maxZ) / 2;
        if (st.length == 3) {
            sx = st[0];
            sz = st[2];
        }

        // 选离工作站最近的那面墙
        boolean alongZ = Math.min(Math.abs(sx - minX), Math.abs(sx - maxX))
                <= Math.min(Math.abs(sz - minZ), Math.abs(sz - maxZ));
        boolean nearMin = alongZ
                ? Math.abs(sx - minX) <= Math.abs(sx - maxX)
                : Math.abs(sz - minZ) <= Math.abs(sz - maxZ);

        int aMin = alongZ ? minZ : minX;
        int aMax = alongZ ? maxZ : maxX;
        int len = aMax - aMin + 1;
        if (len < 6 || (!alongZ && maxZ - minZ + 1 < 4) || (alongZ && maxX - minX + 1 < 4)) {
            return keep;                                  // 区域太窄，不做楼梯
        }

        boolean startAtMin = alongZ ? (sz <= (minZ + maxZ) / 2) : (sx <= (minX + maxX) / 2);
        int dir = startAtMin ? 1 : -1;
        int a0 = startAtMin ? 1 : len - 2;                // 0 基坐标，楼梯口留一格

        int edge = nearMin ? (alongZ ? minX : minZ) : (alongZ ? maxX : maxZ);
        int inward = nearMin ? 1 : -1;
        int[] bands = {edge, edge + inward, edge + inward * 2, edge + inward * 3};
        for (int b : bands) {
            int c = alongZ ? b : b;                        // 同一套判定
            if (alongZ ? (c < minX || c > maxX) : (c < minZ || c > maxZ)) {
                return keep;
            }
        }

        int stairTop = Math.max(bnd[0].getY(), bnd[1].getY());

        for (int run = Math.min(STAIR_RUN_MAX, len - 2); run >= 3; run--) {
            java.util.List<int[]> path = new java.util.ArrayList<>();
            java.util.List<int[]> cells = buildStairCells(len, run, stairTop, bottomY, a0, dir, path);
            if (cells.isEmpty()) {
                continue;
            }
            if (!stairValid(cells, len, aMin)) {
                continue;
            }
            for (int[] c : cells) {
                int along = aMin + c[0];
                int level = c[1];
                int w = c[2];
                int bandCoord = bands[w];
                int px = alongZ ? bandCoord : along;
                int pz = alongZ ? along : bandCoord;
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                    continue;
                }
                keep.add(new BlockPos(px, level, pz));
            }
            // 有序路点：从楼梯口（顶部）到底部
            java.util.List<BlockPos> ordered = new java.util.ArrayList<>();
            for (int[] c : path) {
                int along = aMin + c[0];
                int bandCoord = bands[c[2]];
                int px = alongZ ? bandCoord : along;
                int pz = alongZ ? along : bandCoord;
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                    continue;
                }
                ordered.add(new BlockPos(px, c[1], pz));
            }
            STAIR_PATH_HOLDER.set(ordered);
            return keep;
        }
        return keep;
    }

    /** 生成台阶格：{along, level, width 0..3} —— 转角放一整排 4 格宽的平台 */
    private static java.util.List<int[]> buildStairCells(int len, int run, int top, int bottom,
                                                         int a0, int dir,
                                                         java.util.List<int[]> path) {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        int level = top;
        int a = a0;
        int d = dir;
        int band = 0;
        int guard = 0;
        while (level > bottom && guard++ < 400) {
            int steps = 0;
            while (steps < run && level > bottom && a >= 0 && a < len) {
                for (int w = 0; w < 2; w++) {
                    cells.add(new int[]{a, level, band * 2 + w});
                }
                path.add(new int[]{a, level, band * 2});        // 走路用的中心格
                level--;
                a += d;
                steps++;
            }
            if (level <= bottom) {
                break;
            }
            if (a < 0 || a >= len) {
                a -= d;                                // 回到边界那一格
            }
            // 转角平台：4 格宽（两条跑道全铺），比上一级低 1
            for (int w = 0; w < 4; w++) {
                cells.add(new int[]{a, level, w});
            }
            path.add(new int[]{a, level, 0});                    // 平台上取一格作路点
            // 下一跑：退回一格、再降一格、换跑道掉头
            a -= d;
            level--;
            d = -d;
            band = 1 - band;
        }
        return cells;
    }

    /**
     * 顺着台阶一级一级走。
     *
     * <p>为什么不用原版寻路：楼梯第一段往往"背离"目标（往下走才是楼梯），
     * 寻路按距离算分时认为这一步在走远 → 不肯走 / 在转角打转。
     * 这里改成我们自己按顺序喂路点，每次只让它走 1~3 格。</p>
     *
     * @return true 表示这一步已经接管了移动
     */
    private static boolean stairNavigate(ServerLevel level, EntityMaid maid, Progress p, BlockPos goal) {
        if (p.stairPath.isEmpty()) {
            return false;
        }
        int size = p.stairPath.size();
        BlockPos from = maid.blockPosition();

        // 已经离工作站很近了 → 交给普通寻路（它擅长最后这几步）
        if (from.distSqr(goal) <= 64.0D) {
            p.stairIndex = -1;
            return false;
        }

        if (p.stairIndex < 0 || p.stairIndex >= size) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                double d = p.stairPath.get(i).distSqr(from);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            p.stairIndex = best;
        }

        BlockPos cur = p.stairPath.get(p.stairIndex);
        if (cur.distSqr(from) < 2.25D) {
            boolean goingUp = goal.getY() > from.getY();
            int next = p.stairIndex + (goingUp ? -1 : 1);
            if (next < 0 || next >= size) {
                // 走到楼梯尽头了 → 交还给普通寻路（不要再原地打转）
                p.stairIndex = -1;
                p.stairWaitTicks = 0;
                return false;
            }
            p.stairIndex = next;
            p.stairWaitTicks = 0;
            cur = p.stairPath.get(next);
        }

        // 路点走不到太久 → 跳过这一级继续往前走（绝不清零重找，否则会倒退打转）
        p.stairWaitTicks++;
        if (p.stairWaitTicks > 60) {
            p.stairWaitTicks = 0;
            boolean up = goal.getY() > from.getY();
            int skip = p.stairIndex + (up ? -2 : 2);
            if (skip >= 0 && skip < size) {
                p.stairIndex = skip;
            } else {
                p.stairIndex = -1;          // 真到头了 → 交还普通寻路
                return false;
            }
        }

        if (p.heartbeat % 8 == 0 || maid.getNavigation().isDone()) {
            maid.getNavigation().moveTo(cur.getX() + 0.5D, cur.getY(), cur.getZ() + 0.5D, 0.6D);
        }
        return true;
    }

    /**
     * 把台阶缺的那一块补上（原位置是沙砾/沙子/矿物，挖掉后用背包里的实心方块铺一块）。
     *
     * @return true 表示这一 tick 已经去补块了
     */
    private static boolean fillStairStep(ServerLevel level, EntityMaid maid, Progress p) {
        if (p.stairFill.isEmpty()) {
            return false;
        }
        BlockPos near = null;
        double nd = Double.MAX_VALUE;
        for (BlockPos pos : p.stairFill) {
            double d = pos.distSqr(maid.blockPosition());
            if (d > 36.0D) {
                continue;
            }
            if (d < nd) {
                nd = d;
                near = pos;
            }
        }
        if (near == null) {
            return false;
        }
        BlockState here = level.getBlockState(near);
        if (!here.isAir()) {
            return false;                              // 还没挖掉 / 已经有方块了
        }
        var inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                continue;
            }
            BlockState placed = bi.getBlock().defaultBlockState();
            if (!isStableStep(placed)) {
                continue;                              // 别拿沙砾去补台阶
            }
            level.setBlock(near, placed, 3);
            PlacedTracker.record(level, near, bi.getBlock(), maid.getUUID());
            stack.shrink(1);
            if (stack.isEmpty()) {
                inv.setStackInSlot(i, ItemStack.EMPTY);
            }
            p.stairFill.remove(near);
            MaidMining.LOGGER.info("[补台阶] 在 {} 铺了 {}", near, placed.getBlock());
            return true;
        }
        return false;
    }

    /** 她是不是站在台阶的某一端（顶端/底端）——这一段允许垫高，因为常常差 1~2 格跳不上来 */
    static boolean nearStairEnd(EntityMaid maid) {
        Progress p = PROGRESS.get(maid.getUUID());
        if (p == null || p.stairPath.isEmpty()) {
            return false;
        }
        BlockPos from = maid.blockPosition();
        BlockPos top = p.stairPath.get(0);
        BlockPos bottom = p.stairPath.get(p.stairPath.size() - 1);
        return from.distSqr(top) <= 25.0D || from.distSqr(bottom) <= 25.0D;
    }

    private static final String NBT_STAIR_CELLS = "maidmining_stair_cells";

    /** 把台阶坐标存进女仆 NBT（跨会话保留，防止旧台阶失去保护） */
    private static void saveStairCells(EntityMaid maid, java.util.Set<BlockPos> cells) {
        long[] arr = new long[cells.size()];
        int i = 0;
        for (BlockPos pos : cells) {
            arr[i++] = pos.asLong();
        }
        maid.getPersistentData().putLongArray(NBT_STAIR_CELLS, arr);
    }

    /** 读出以前记录过的台阶，合并进当前集合 */
    private static void loadStairCells(EntityMaid maid, java.util.Set<BlockPos> into) {
        long[] arr = maid.getPersistentData().getLongArray(NBT_STAIR_CELLS);
        for (long v : arr) {
            into.add(BlockPos.of(v));
        }
    }

    /** 这格是不是我们留的台阶（是的话任何情况都不许挖） */
    static boolean isStairCell(EntityMaid maid, BlockPos pos) {
        Progress p = PROGRESS.get(maid.getUUID());
        return p != null && p.stairCells.contains(pos);
    }

    /** 这块区域有没有留下台阶（有的话就不许垫高，逼她走台阶） */
    static boolean hasStairs(EntityMaid maid) {
        Progress p = PROGRESS.get(maid.getUUID());
        return p != null && !p.stairCells.isEmpty();
    }

    /** 附近（给定距离内）有没有台阶格 */
    static boolean nearStairCell(EntityMaid maid, double radius) {
        Progress p = PROGRESS.get(maid.getUUID());
        if (p == null || p.stairCells.isEmpty()) {
            return false;
        }
        double r2 = radius * radius;
        for (BlockPos c : p.stairCells) {
            if (c.distSqr(maid.blockPosition()) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选"下一级台阶"：朝着 goal 的方向，只差 1 层的最近那格。
     * 一次只走一级，且锁定到走到为止 —— 否则会在两层之间来回跑。
     */
    private static BlockPos pickStairStep(Progress p, BlockPos from, BlockPos goal) {
        int dir = goal.getY() > from.getY() ? 1 : -1;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : p.stairCells) {
            int dy = c.getY() - from.getY();
            if (dy * dir <= 0 || Math.abs(dy) > 2) {
                continue;                                  // 只要朝目标方向、差 1~2 层
            }
            double score = Math.abs(dy) * 10.0D + c.distSqr(from);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }


    /** 校验：不越界、同列不叠相邻层、上下都能走通 */
    private static boolean stairValid(java.util.List<int[]> cells, int len, int aMin) {
        java.util.Set<String> all = new java.util.HashSet<>();
        java.util.Map<String, java.util.TreeSet<Integer>> cols = new java.util.HashMap<>();
        for (int[] c : cells) {
            if (c[0] < 0 || c[0] >= len || c[2] < 0 || c[2] > 3) {
                return false;
            }
            all.add(c[0] + "," + c[1] + "," + c[2]);
            cols.computeIfAbsent(c[0] + "," + c[2], k -> new java.util.TreeSet<>()).add(c[1]);
        }
        for (var e : cols.entrySet()) {
            var lv = e.getValue();
            Integer prev = null;
            for (int v : lv) {
                if (prev != null && Math.abs(v - prev) <= 1) {
                    return false;                        // 同列相邻两层 = 2 格高的墙
                }
                prev = v;
            }
        }
        // 连通性：从最高的那块出发，8 邻域 + 落差 1 走遍全部
        int[] top = null;
        for (int[] c : cells) {
            if (top == null || c[1] > top[1]) {
                top = c;
            }
        }
        if (top == null) {
            return false;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Deque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(top);
        seen.add(top[0] + "," + top[1] + "," + top[2]);
        while (!stack.isEmpty()) {
            int[] c = stack.pop();
            for (int da = -1; da <= 1; da++) {
                for (int dw = -1; dw <= 1; dw++) {
                    for (int dl = -1; dl <= 1; dl += 2) {
                        if (da == 0 && dw == 0) {
                            continue;
                        }
                        String key = (c[0] + da) + "," + (c[1] + dl) + "," + (c[2] + dw);
                        if (all.contains(key) && seen.add(key)) {
                            stack.push(new int[]{c[0] + da, c[1] + dl, c[2] + dw});
                        }
                    }
                }
            }
        }
        return seen.size() == all.size();
    }

    private void buildList(ServerLevel level, EntityMaid maid, Progress p, BlockPos[] bnd, String sig,
                           int myIndex, int total) {
        int down = maid.getPersistentData().getInt(NBT_DEPTH);
        if (!maid.getPersistentData().contains(NBT_DEPTH)) {
            down = DEFAULT_DEPTH;
        }
        if (down < 0 || down > 256) {
            down = 0;
        }
        if (down == 0) {
            down = 1;
        }
        int up = Math.max(0, Math.min(128, maid.getPersistentData().getInt(NBT_UP)));

        int cornerTop = Math.max(bnd[0].getY(), bnd[1].getY());
        int topY = Math.min(level.getMaxBuildHeight() - 1, cornerTop + up);
        int bottomY = Math.max(level.getMinBuildHeight(), cornerTop - down + 1);

        int x1 = bnd[0].getX();
        int x2 = bnd[1].getX();
        // 不分条：所有女仆挖同一片，一层一层往下（靠"抢格子"避免重复）
        // 分条会让各人进度不一样 → 一边挖到深处、另一边还高高的

        // ===== 双跑回转楼梯（贴离工作站最近的那面墙）=====
        java.util.Set<BlockPos> stairKeep = stairKeepSet(maid, bnd, bottomY);
        // 老台阶（以前版本留下的）也要一起保护，否则会被当成普通方块挖掉
        loadStairCells(maid, stairKeep);
        p.stairCells = stairKeep;
        p.stairPath = new java.util.ArrayList<>(STAIR_PATH_HOLDER.get());
        p.stairIndex = -1;
        saveStairCells(maid, stairKeep);
        int kept = 0;

        List<BlockPos> list = new ArrayList<>();
        for (int y = topY; y >= bottomY; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = Math.min(bnd[0].getZ(), bnd[1].getZ());
                        z <= Math.max(bnd[0].getZ(), bnd[1].getZ()); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (stairKeep.contains(pos)) {
                        if (isStableStep(level.getBlockState(pos))) {
                            kept++;
                            continue;                  // 台阶：原地保留
                        }
                        // 会掉落/是矿物 → 这一格还是要挖，但挖完要用圆石补一块
                        p.stairFill.add(pos);
                    }
                    if (PlacedTracker.isOurs(pos)) {
                        continue;                      // 自己补的方块，不挖
                    }
                    if (isMineable(level.getBlockState(pos))) {
                        list.add(pos);
                    }
                }
            }
        }
        p.remaining = new HashSet<>(list);
        p.areaSig = sig;
        p.topLayerDirty = true;
        p.mined = 0;
        p.target = null;
        p.waitTicks = 0;
        MaidMining.LOGGER.info("[分配] 区域 {} → {}，可挖 {} 格（y {} → {}），留台阶 {} 格（第 {}/{} 只）",
                bnd[0], bnd[1], list.size(), topY, bottomY, kept, myIndex + 1, total);
    }

    /** 同一片区域里有几只女仆，我是第几只（按 UUID 排序，稳定） */
    private static int[] groupIndex(ServerLevel level, EntityMaid maid, BlockPos[] bnd) {
        int[] mine = maid.getPersistentData().getIntArray(EntityMaidArea.TAG);
        if (mine.length != 6) {
            return new int[]{0, 1};
        }
        var box = new net.minecraft.world.phys.AABB(
                Math.min(bnd[0].getX(), bnd[1].getX()) - 256, level.getMinBuildHeight(),
                Math.min(bnd[0].getZ(), bnd[1].getZ()) - 256,
                Math.max(bnd[0].getX(), bnd[1].getX()) + 256, level.getMaxBuildHeight(),
                Math.max(bnd[0].getZ(), bnd[1].getZ()) + 256);
        List<EntityMaid> same = new ArrayList<>();
        for (EntityMaid m : level.getEntitiesOfClass(EntityMaid.class, box)) {
            int[] a = m.getPersistentData().getIntArray(EntityMaidArea.TAG);
            if (a.length == 6 && java.util.Arrays.equals(a, mine)) {
                same.add(m);
            }
        }
        same.sort(java.util.Comparator.comparing(m -> m.getUUID().toString()));
        for (int i = 0; i < same.size(); i++) {
            if (same.get(i).getId() == maid.getId()) {
                return new int[]{i, same.size()};
            }
        }
        return new int[]{0, Math.max(1, same.size())};
    }

    /**
     * 挑一个要挖的方块。
     *
     * <p>清理模式：全体对齐到「整个区域最高、还没人认领的那一层」，同层挑最近的
     * —— 这样所有女仆都在同一层干活，不会一边挖到深处、另一边还高高的。</p>
     *
     * <p>找矿模式：挑最近的矿。</p>
     */
    private BlockPos pickTarget(BlockPos from, Progress p, EntityMaid maid) {
        long now = System.currentTimeMillis() / 50L;
        boolean oreMode = maid.getPersistentData().getInt("maidmining_mode") == 1;

        if (oreMode) {
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos pos : p.remaining) {
                if (skipThis(pos, p, maid, now)) {
                    continue;
                }
                double d = pos.distSqr(from);
                if (d < bd) {
                    bd = d;
                    best = pos;
                }
            }
            if (best != null) {
                claim(best, maid, now);
            }
            return best;
        }

        // 清理模式：先定"该挖哪一层"
        int topLayer = topLayerOf(p, maid, now);
        if (topLayer == Integer.MIN_VALUE) {
            return null;
        }

        BlockPos bestTop = null, bestAny = null;
        double dTop = Double.MAX_VALUE, dAny = Double.MAX_VALUE;
        for (BlockPos pos : p.remaining) {
            if (skipThis(pos, p, maid, now)) {
                continue;
            }
            double d = pos.distSqr(from);
            if (pos.getY() == topLayer && d < dTop) {
                dTop = d;
                bestTop = pos;
            }
            if (d < dAny) {
                dAny = d;
                bestAny = pos;
            }
        }
        BlockPos best = bestTop != null ? bestTop : bestAny;
        if (best != null) {
            claim(best, maid, now);
        }
        return best;
    }

    /** 跳过：被别人认领 / 在解禁期内 */
    private static boolean skipThis(BlockPos pos, Progress p, EntityMaid maid, long now) {
        if (claimedByOther(pos, maid, now)) {
            return true;
        }
        Long until = p.skipUntil.get(pos);
        if (until != null) {
            if (until > now) {
                return true;
            }
            p.skipUntil.remove(pos);
        }
        return false;
    }

    /** 未认领方块里最高的一层；缓存起来，区域变化时置脏重算 */
    private static int topLayerOf(Progress p, EntityMaid maid, long now) {
        if (p.topLayer != Integer.MIN_VALUE && !p.topLayerDirty) {
            return p.topLayer;
        }
        int top = Integer.MIN_VALUE;
        for (BlockPos pos : p.remaining) {
            if (claimedByOther(pos, maid, now)) {
                continue;
            }
            if (pos.getY() > top) {
                top = pos.getY();
            }
        }
        p.topLayer = top;
        p.topLayerDirty = false;
        return top;
    }

    // ------------------------------------------------------------------

    private static BlockPos standPos(ServerLevel level, BlockPos target, BlockPos from) {
        int ty = target.getY();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos side = new BlockPos(target.getX() + d[0], ty, target.getZ() + d[1]);
            if (!free(level, side) || !free(level, side.above())) {
                continue;
            }
            if (!level.getBlockState(side.below()).isSolid()) {
                continue;
            }
            double dd = side.distSqr(from);
            if (dd < bestD) {
                bestD = dd;
                best = side;
            }
        }
        return best;
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static BlockPos approachBlock(ServerLevel level, EntityMaid maid, BlockPos ore,
                                          Set<BlockPos> remaining) {
        int[][] dirs = {{0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}};
        for (int[] d : dirs) {
            BlockPos side = ore.offset(d[0], d[1], d[2]);
            if (isStairCell(maid, side)) {
                continue;                        // 台阶不许当开路方块
            }
            if (remaining.contains(side)) {
                return side;
            }
            var st = level.getBlockState(side);
            if (st.isAir() || st.hasBlockEntity() || !st.getFluidState().isEmpty()) {
                continue;
            }
            if (standPos(level, side, ore) != null || standPos(level, ore, side) != null) {
                return side;
            }
        }
        return null;
    }

    private static BlockPos safeSpotAbove(ServerLevel level, BlockPos target) {
        for (int i = 0; i < 4; i++) {
            BlockPos p = target.above(i);
            if (free(level, p) && free(level, p.above()) && level.getBlockState(p.below()).isSolid()) {
                return p;
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos p = target.offset(d[0], 1, d[1]);
            if (free(level, p) && free(level, p.above()) && level.getBlockState(p.below()).isSolid()) {
                return p;
            }
        }
        return null;
    }

    private static BlockPos areaTopSpot(ServerLevel level, BlockPos[] bnd, BlockPos near) {
        int top = Math.max(bnd[0].getY(), bnd[1].getY()) + 2;
        for (int dy = 0; dy < 10; dy++) {
            BlockPos p = new BlockPos(near.getX(), top - dy, near.getZ());
            if (free(level, p) && free(level, p.above())) {
                return p;
            }
        }
        return new BlockPos(near.getX(), top, near.getZ());
    }

    private static BlockPos randomSpotIn(BlockPos[] bnd) {
        int minX = Math.min(bnd[0].getX(), bnd[1].getX());
        int maxX = Math.max(bnd[0].getX(), bnd[1].getX());
        int minZ = Math.min(bnd[0].getZ(), bnd[1].getZ());
        int maxZ = Math.max(bnd[0].getZ(), bnd[1].getZ());
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        return new BlockPos(rnd.nextInt(minX, maxX + 1),
                Math.max(bnd[0].getY(), bnd[1].getY()),
                rnd.nextInt(minZ, maxZ + 1));
    }

    private static BlockPos nearestSpot(ServerLevel level, EntityMaid maid, Progress p) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : p.remaining) {
            BlockPos spot = safeSpotAbove(level, pos);
            if (spot == null) {
                continue;
            }
            double d = spot.distSqr(maid.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = spot;
            }
        }
        return best;
    }

    private static boolean buildPlatform(ServerLevel level, EntityMaid maid, BlockPos target) {
        var inv = maid.getMaidInv();
        int slot = BlockPicking.findSlot(inv);
        if (slot < 0) {
            return false;
        }
        ItemStack stack = inv.getStackInSlot(slot);
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
            return false;
        }
        BlockPos spot = target.above();
        if (!free(level, spot) || !free(level, spot.above())) {
            return false;
        }
        level.setBlock(spot, bi.getBlock().defaultBlockState(), 3);
        PlacedTracker.record(level, spot, bi.getBlock(), maid.getUUID());
        stack.shrink(1);
        if (stack.isEmpty()) {
            inv.setStackInSlot(slot, ItemStack.EMPTY);
        }
        maid.teleportTo(spot.getX() + 0.5D, spot.getY() + 1.0D, spot.getZ() + 0.5D);
        maid.getNavigation().stop();
        maid.fallDistance = 0.0F;
        return true;
    }

    private static void sealFluids(ServerLevel level, EntityMaid maid, BlockPos mined) {
        var inv = maid.getMaidInv();
        int slot = BlockPicking.findSlot(inv);
        if (slot < 0) {
            return;
        }
        ItemStack stack = inv.getStackInSlot(slot);
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
            return;
        }
        int plugged = 0;
        for (Direction d : Direction.values()) {
            if (plugged >= 2) {
                break;
            }
            BlockPos n = mined.relative(d);
            var st = level.getBlockState(n);
            if (!st.getFluidState().isEmpty() && st.getFluidState().isSource()) {
                level.setBlock(n, bi.getBlock().defaultBlockState(), 3);
                PlacedTracker.record(level, n, bi.getBlock(), maid.getUUID());
                stack.shrink(1);
                plugged++;
                if (stack.isEmpty()) {
                    inv.setStackInSlot(slot, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }

    /** 挖掉一个方块，掉落直接进背包（挖隧道用，避免一地掉落物） */
    static int harvest(ServerLevel level, EntityMaid maid, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0;
        }
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, maid, maid.getMainHandItem());
        level.destroyBlock(pos, false);
        for (ItemStack stack : drops) {
            ItemStack rest = com.gzx.maidmining.StationStorage.give(maid, stack);
            if (!rest.isEmpty()) {
                Block.popResource(level, pos, rest);   // 背包满 → 才掉地上
            }
        }
        return 1;
    }

    private int mineOne(ServerLevel level, EntityMaid maid, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isMineable(state)) {
            return 0;
        }
        boolean valuable = true;   // 全部捡（玩家要求：挖到的方块都收）
        List<ItemStack> drops = valuable
                ? Block.getDrops(state, level, pos, null, maid, maid.getMainHandItem())
                : List.of();
        level.destroyBlock(pos, false);

        if (valuable) {
            if (drops.isEmpty()) {
                List<ItemStack> again = new ArrayList<>();
                state.getDrops(new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                                net.minecraft.world.phys.Vec3.atCenterOf(pos))
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, maid.getMainHandItem())
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, maid)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, state)).forEach(again::add);
                drops = again;
            }
            boolean bagFull = false;
            for (ItemStack stack : drops) {
                ItemStack rest = com.gzx.maidmining.StationStorage.give(maid, stack);
                if (!rest.isEmpty()) {
                    bagFull = true;
                    Block.popResource(level, pos, rest);   // 背包满了 → 多的掉地上
                }
            }
            return bagFull ? 2 : 1;
        }
        return 1;
    }

    private boolean giveToMaid(ServerLevel level, EntityMaid maid, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy());
        entity.setNoPickUpDelay();
        level.addFreshEntity(entity);
        boolean ok;
        try {
            ok = maid.pickupItem(entity, false);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok && !entity.isRemoved()) {
            entity.discard();
        }
        return ok;
    }

    private boolean isMineable(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.is(Blocks.BEDROCK) && !state.is(Blocks.OBSIDIAN) && !state.is(Blocks.CRYING_OBSIDIAN);
    }

    private boolean isValuable(BlockState state) {
        return state.is(Blocks.ANDESITE) || isOre(state);
    }

    private boolean isOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE)
                || state.is(Blocks.ANCIENT_DEBRIS);
    }

    /** 壁挂火把：贴在「区域之外、以后不会挖掉」的墙上 */
    private void placeWallTorch(ServerLevel level, BlockPos pos, Set<BlockPos> remaining, BlockPos[] area) {
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wall = pos.relative(dir);
            if (remaining.contains(wall)) {
                continue;
            }
            if (area != null && inside(wall, area)) {
                continue;
            }
            if (!level.getBlockState(wall).isFaceSturdy(level, wall, dir.getOpposite())) {
                continue;
            }
            level.setBlockAndUpdate(pos, Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(WallTorchBlock.FACING, dir.getOpposite()));
            return;
        }
    }

    private static boolean insideArea(BlockPos p, BlockPos[] bounds) {
        return p.getX() >= bounds[0].getX() - 8 && p.getX() <= bounds[1].getX() + 8
                && p.getZ() >= bounds[0].getZ() - 8 && p.getZ() <= bounds[1].getZ() + 8
                && p.getY() >= bounds[0].getY() - 96 && p.getY() <= bounds[1].getY() + 64;
    }

    private static boolean inside(BlockPos p, BlockPos[] bounds) {
        return p.getX() >= bounds[0].getX() && p.getX() <= bounds[1].getX()
                && p.getY() >= bounds[0].getY() && p.getY() <= bounds[1].getY()
                && p.getZ() >= bounds[0].getZ() && p.getZ() <= bounds[1].getZ();
    }
}
