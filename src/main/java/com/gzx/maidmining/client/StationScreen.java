package com.gzx.maidmining.client;

import com.gzx.maidmining.menu.StationMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/** 工作站界面：御币槽 + 三个按钮 + 女仆状态列表（带翻页）。 */
public class StationScreen extends AbstractContainerScreen<StationMenu> {

    /** 每页显示多少只 */
    private static final int PER_PAGE = 6;
    /** 每行高度 */
    private static final int LINE_H = 10;

    private int page = 0;

    public StationScreen(StationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 268;
        this.inventoryLabelY = 1000;   // 不画"物品栏"
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("召回 64 格内女仆"), b -> send(0))
                .bounds(this.leftPos + 8, this.topPos + 34, 160, 18).build());
        addRenderableWidget(Button.builder(Component.literal("重新发放工作区"), b -> send(1))
                .bounds(this.leftPos + 8, this.topPos + 52, 160, 18).build());
        boolean paused = this.menu.data().get(2) != 0;
        addRenderableWidget(Button.builder(Component.literal(paused ? "复工（当前停工中）" : "一键停工"),
                        b -> send(2))
                .bounds(this.leftPos + 8, this.topPos + 70, 160, 18).build());

        // 翻页箭头
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                    if (page > 0) {
                        page--;
                    }
                })
                .bounds(this.leftPos + 118, this.topPos + 92, 18, 12).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> page++)
                .bounds(this.leftPos + 150, this.topPos + 92, 18, 12).build());
    }

    private void send(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font, "在挖的女仆：" + this.menu.data().get(0),
                this.leftPos + 8, this.topPos + 94, 0x404040, false);

        List<String> lines = StationStatusCache.get(this.menu.pos());
        int pages = Math.max(1, (lines.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        g.drawString(this.font, (page + 1) + "/" + pages, this.leftPos + 138, this.topPos + 94, 0x606060, false);

        int ly = this.topPos + 108;
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(lines.size(), from + PER_PAGE); i++) {
            String text = lines.get(i).replace('|', ' ');
            if (this.font.width(text) > 160) {
                text = this.font.plainSubstrByWidth(text, 156) + "…";
            }
            g.drawString(this.font, text, this.leftPos + 8, ly, 0x1A3A5F, false);
            ly += LINE_H;
        }
        if (lines.isEmpty()) {
            g.drawString(this.font, "（还没有装了工牌的女仆）", this.leftPos + 8, ly, 0x808080, false);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        g.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + this.imageWidth - 1, y + 16, 0xFF8B8B8B);
        slotFrame(g, x + 16, y + 19);
        // 玩家背包格子（3 行 + 快捷栏），不然看不出分格
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotFrame(g, x + 8 + col * 18, y + 178 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slotFrame(g, x + 8 + col * 18, y + 236);
        }
    }

    private void slotFrame(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF373737);
    }
}
