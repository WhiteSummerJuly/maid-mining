package com.gzx.maidmining.world;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gzx.maidmining.MaidMining;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗女仆守矿区。
 *
 * <p>不接管她的战斗 AI，只做四件事：</p>
 * <ol>
 *   <li>把她的活动范围限制在矿区（restrictTo）</li>
 *   <li>日程位置设成矿区中心（TML 会让她往那儿靠）</li>
 *   <li>保持 home 模式（关掉会被传送到主人身边）</li>
 *   <li><b>没事的时候在矿区里巡逻</b>（每隔几秒挑一个区域内的点走过去）</li>
 * </ol>
 *
 * <p>有攻击目标 / 正在吃东西 / 正在坐着时不动她，让她自己处理。</p>
 */
@EventBusSubscriber(modid = MaidMining.MOD_ID)
public class AreaGuard {

    /** 每只女仆下一次巡逻的时刻 */
    private static final Map<UUID, Long> NEXT_PATROL = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid)) {
                    continue;
                }
                int[] a = maid.getPersistentData().getIntArray("maidmining_area");
                if (a.length != 6) {
                    continue;
                }
                if (!(maid.getTask() instanceof IAttackTask)) {
                    continue;
                }
                guard(level, maid, a);
            }
        }
    }

    private static void guard(ServerLevel level, EntityMaid maid, int[] a) {
        int minX = Math.min(a[0], a[3]);
        int minY = Math.min(a[1], a[4]);
        int minZ = Math.min(a[2], a[5]);
        int maxX = Math.max(a[0], a[3]);
        int maxY = Math.max(a[1], a[4]);
        int maxZ = Math.max(a[2], a[5]);

        BlockPos center = new BlockPos((minX + maxX) / 2, maxY, (minZ + maxZ) / 2);
        int half = Math.max(maxX - minX, maxZ - minZ) / 2 + 10;

        if (!maid.hasRestriction()
                || !center.equals(maid.getRestrictCenter())
                || (int) maid.getRestrictRadius() != half) {
            maid.restrictTo(center, half);
        }
        if (!maid.isHomeModeEnable()) {
            maid.setHomeModeEnable(true);
        }
        // 日程位置必须跟着她走 —— 车万女仆每 40 tick 会用「配置里的半径（默认 12）」
        // 重新 restrictTo，然后判断"离家远不远"，远了就传送。
        // 把家设成她当前站的地方，就永远不会被判成"离家太远"。
        var sp = maid.getSchedulePos();
        if (sp != null) {
            BlockPos here = maid.blockPosition();
            if (!here.equals(sp.getWorkPos())) {
                sp.setWorkPos(here);
            }
            if (!here.equals(sp.getIdlePos())) {
                sp.setIdlePos(here);
            }
            if (!here.equals(sp.getSleepPos())) {
                sp.setSleepPos(here);
            }
            if (!sp.isConfigured()) {
                sp.setConfigured(true);
            }
        }

        // 工作站点了「一键停工」→ 不巡逻，老实站着
        if (maid.getPersistentData().getBoolean("maidmining_paused")) {
            maid.getNavigation().stop();
            return;
        }

        // ---- 巡逻 ----
        if (maid.getTarget() != null || maid.isMaidInSittingPose()) {
            return;                      // 在打架 / 坐着，别打扰
        }
        if (maid.getNavigation().isInProgress()) {
            return;                      // 正在走过去
        }
        long now = level.getGameTime();
        Long next = NEXT_PATROL.get(maid.getUUID());
        if (next != null && now < next) {
            return;
        }
        BlockPos spot = randomStand(level, minX, minY, minZ, maxX, maxY, maxZ);
        if (spot != null) {
            maid.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.6D);
            MaidMining.debug("[debug] 矿区守卫巡逻点 {}", spot);
        }
        NEXT_PATROL.put(maid.getUUID(), now + 100 + ThreadLocalRandom.current().nextInt(80));
    }

    /** 在区域里随便找一个能站的格子（从区域顶部往下找 12 格） */
    private static BlockPos randomStand(ServerLevel level, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ) {
        var rnd = ThreadLocalRandom.current();
        int x = rnd.nextInt(minX, maxX + 1);
        int z = rnd.nextInt(minZ, maxZ + 1);
        int top = Math.min(level.getMaxBuildHeight() - 2, maxY + 2);
        int bottom = Math.max(level.getMinBuildHeight() + 1, minY - 12);
        for (int y = top; y >= bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir()
                    && level.getBlockState(p.below()).isSolid()) {
                return p;
            }
        }
        return null;
    }
}
