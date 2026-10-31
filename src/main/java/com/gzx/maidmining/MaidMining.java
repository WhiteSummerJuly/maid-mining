package com.gzx.maidmining;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.gzx.maidmining.MiningTask;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.gzx.maidmining.item.MiningGoheiItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 女仆矿业 —— 车万女仆附属
 * <p>
 * 让女仆替你螺旋下挖：玩家指定深度，只捡矿物与安山岩，自动插火把。
 */
@Mod(MaidMining.MOD_ID)
public class MaidMining {
    public static final String MOD_ID = "maidmining";
    public static final Logger LOGGER = LoggerFactory.getLogger("MaidMining");

    /** 本 mod 提供的任务实例（全局唯一） */
    public static final IMaidTask MINING_TASK = new MiningTask();

    /** 物品注册表 */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    /** 矿区工牌（饰品） */
    public static final DeferredItem<net.minecraft.world.item.Item> MINING_BADGE =
            ITEMS.registerItem("mining_badge", com.gzx.maidmining.item.MiningBadgeItem::new);

    /** 界面注册表 */
    public static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.MENU, MOD_ID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<com.gzx.maidmining.menu.MiningConfigMenu>> MINING_CONFIG_MENU =
            MENUS.register("mining_config", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    com.gzx.maidmining.menu.MiningConfigMenu::fromNetwork));

    /** 方块注册表 */
    public static final net.neoforged.neoforge.registries.DeferredRegister.Blocks BLOCKS =
            net.neoforged.neoforge.registries.DeferredRegister.createBlocks(MOD_ID);

    /** 女仆矿业工作站 */
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> MINING_STATION =
            BLOCKS.register("mining_station", () -> new com.gzx.maidmining.block.MiningStationBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .requiresCorrectToolForDrops()));



    public static final DeferredItem<net.minecraft.world.item.BlockItem> MINING_STATION_ITEM =
            ITEMS.registerSimpleBlockItem(MINING_STATION);

    /** 方块实体注册表 */
    public static final net.neoforged.neoforge.registries.DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, MOD_ID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<com.gzx.maidmining.block.MiningStationBlockEntity>> MINING_STATION_BE =
            BLOCK_ENTITIES.register("mining_station",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .of(com.gzx.maidmining.block.MiningStationBlockEntity::new, MINING_STATION.get())
                            .build(null));

    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<com.gzx.maidmining.menu.StationMenu>> STATION_MENU =
            MENUS.register("station", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    com.gzx.maidmining.menu.StationMenu::fromNetwork));


    /** 采矿御币 */
    public static final DeferredItem<Item> MINING_GOHEI =
            ITEMS.registerItem("mining_gohei", MiningGoheiItem::new, new Item.Properties().stacksTo(1));

    public MaidMining(IEventBus modBus) {
        ITEMS.register(modBus);
        MENUS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        BLOCKS.register(modBus);
        TABS.register(modBus);

        // 采矿任务期间免疫摔落伤害（挖矿会往下掉，摔死太冤）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MaidMining::onIncomingDamage);
        // 车万女仆重写了 hurt，通用事件对女仆不触发 → 挂它自己的两个事件
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MaidMining::onMaidDamage);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MaidMining::onMaidHurt);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent e) ->
                        com.gzx.maidmining.ai.MineBehavior.clearAll());

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(MaidMining::onMaidDeath);
        modBus.addListener(this::registerPayloads);
        onRegisterConfig(modBus);
        modBus.addListener(this::addCreative);
        LOGGER.info("女仆矿业加载中……");
    }

    /** 独立的 mod 物品页 */
    public static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final java.util.function.Supplier<net.minecraft.world.item.CreativeModeTab> TAB =
            TABS.register("main", () -> net.minecraft.world.item.CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.maidmining"))
                    .icon(() -> new ItemStack(MINING_GOHEI.get()))
                    .displayItems((params, output) -> {
                        output.accept(MINING_GOHEI.get());
                        output.accept(MINING_STATION_ITEM.get());
                        output.accept(MINING_BADGE.get());
                    })
                    .build());


    /** 调试日志：默认不打，配置里 debugLogs=true 才输出 */
    public static void debug(String format, Object... args) {
        if (MaidMiningConfig.debugLogs()) {
            LOGGER.info(format, args);
        }
    }

    private static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return;
        }
        // 只在「采矿」任务期间免伤
        if (maid.getTask() == null || !MINING_TASK.getUid().equals(maid.getTask().getUid())) {
            return;
        }
        var src = event.getSource();
        // 虚空、强制击杀这类"世界规则"不动，其余在采矿期间一律免伤
        String msg = src.getMsgId();
        if ("genericKill".equals(msg) || "outOfWorld".equals(msg) || "fellOutOfWorld".equals(msg)) {
            return;   // 强制击杀 / 虚空，不拦
        }
        event.setCanceled(true);
    }

    /** 女仆死了 → 把她留下的运行时状态清掉，别一直堆在静态表里 */
    private static void onMaidDeath(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidDeathEvent event) {
        var maid = event.getMaid();
        if (maid != null) {
            com.gzx.maidmining.ai.MineBehavior.forgetMaid(maid.getUUID(), maid.getId());
        }
    }

    /** 车万女仆自己的伤害事件（会触发） */
    private static void onMaidDamage(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidDamageEvent event) {
        if (immuneFor(event.getMaid())) {
            LOGGER.info("[debug] 挡住伤害（MaidDamage）：{}", event.getSource().getMsgId());
            event.setCanceled(true);
        }
    }

    private static void onMaidHurt(
            com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtEvent event) {
        if (immuneFor(event.getMaid())) {
            LOGGER.info("[debug] 挡住伤害（MaidHurt）：{}", event.getSource().getMsgId());
            event.setCanceled(true);
        }
    }

    private static boolean immuneFor(
            com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        if (maid.getTask() == null || !MINING_TASK.getUid().equals(maid.getTask().getUid())) {
            return false;
        }
        return true;
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MOD_ID);
        registrar.playToClient(com.gzx.maidmining.net.StationStatusPayload.TYPE,
                com.gzx.maidmining.net.StationStatusPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.gzx.maidmining.client.StationStatusCache.put(payload.pos(), payload.lines())));
    }

    private static void onRegisterConfig(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(() -> net.neoforged.fml.ModList.get()
                        .getModContainerById(MOD_ID)
                        .ifPresent(c -> c.registerConfig(
                                net.neoforged.fml.config.ModConfig.Type.COMMON, MaidMiningConfig.SPEC))));
    }

    private void addCreative(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(MINING_GOHEI);
            event.accept(MINING_STATION_ITEM);
        }
    }

}
