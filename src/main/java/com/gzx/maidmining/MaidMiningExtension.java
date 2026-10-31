package com.gzx.maidmining;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

/**
 * 车万女仆的附属入口。
 * <p>
 * TML 会扫描带 {@code @LittleMaidExtension} 注解的 {@link ILittleMaid} 实现，
 * 并在它自己的初始化流程里回调 {@link #addMaidTask(TaskManager)}。
 * 这里拿到的 TaskManager 是真身，{@code add()} 会写进内部的任务表。
 */
@LittleMaidExtension
public class MaidMiningExtension implements ILittleMaid {

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(MaidMining.MINING_TASK);
        MaidMining.LOGGER.info("已注册女仆任务：{}", MaidMining.MINING_TASK.getUid());
    }

    /** 注册饰品：矿区工牌 */
    @Override
    public void bindMaidBauble(com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager manager) {
        manager.bind(MaidMining.MINING_BADGE.get(), new com.gzx.maidmining.bauble.BadgeBauble());
        MaidMining.LOGGER.info("已注册饰品：矿区工牌");
    }
}
