package com.gzx.maidmining.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.MaidTaskConfigGui;
import com.gzx.maidmining.menu.MiningConfigMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 采矿设置界面 —— 直接复用「攻击列表」那张表格贴图（外框、行线、箭头都是它画好的）。
 *
 * <p>贴图坐标（实测）：外框 x1..173 / y1..176；行线 y=40,52,65,78,...（每行 13px）；
 * 左列 x3..125 放名字，右列 x127..170 放按钮。</p>
 */
public class MiningConfigScreen extends MaidTaskConfigGui<MiningConfigMenu> {

    private static final ResourceLocation TABLE = ResourceLocation.fromNamespaceAndPath(
            "touhou_little_maid", "textures/gui/attack_task_config.png");

    /** 贴图在面板里的落点（和 TML 自己的配置页一致） */
    public static final int BASE_X = 80;
    public static final int BASE_Y = 28;
    /** 表格区域（贴图内的尺寸） */
    public static final int TEX_W = 176;
    /** 只贴到表格本体结束（137）——贴图 y138 以下是"添加输入框"那块，用不上还会盖住背包 */
    public static final int TEX_H = 137;
    /** 第一行行线的 y（贴图内坐标） */
    public static final int ROW0 = 40;
    /** 行距 */
    public static final int ROW_PITCH = 13;   // 一个选项占一行（和它的列表一致）

    public MiningConfigScreen(MiningConfigMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }


    public static int baseAbsX(int leftPos) {
        return leftPos + BASE_X;
    }

    public static int baseAbsY(int topPos) {
        return topPos + BASE_Y;
    }

    public static int rowAbsY(int topPos, int index) {
        return baseAbsY(topPos) + ROW0 + index * ROW_PITCH;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        super.renderBg(g, partialTick, mouseX, mouseY);
        g.blit(TABLE, baseAbsX(this.leftPos), baseAbsY(this.topPos), 0, 0, TEX_W, TEX_H);
    }

    @Override
    protected void renderAddition(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int bx = baseAbsX(this.leftPos);
        int by = baseAbsY(this.topPos);

        // 表头标题（贴图 y4..38 是空着的表头区）
        g.drawString(this.font, "采矿设置", bx + 8, by + 14, 0x3F3F3F, false);

        int depth = this.menu.data().get(0);
        int up = this.menu.data().get(1);
        int torch = this.menu.data().get(2);

        int mode = this.menu.data().get(3);
        int tier = this.menu.data().get(4);
        String[] names = {"向下深度", "向上高度", "火把", "模式", "矿种"};
        String[] values = {String.valueOf(depth), String.valueOf(up),
                torch != 0 ? "开" : "关", mode == 1 ? "找矿" : "清理",
                tier == 0 ? "全部" : tier == 1 ? "铁以上" : "稀有"};

        for (int i = 0; i < 5; i++) {
            int ry = rowAbsY(this.topPos, i) + 3;
            g.drawString(this.font, names[i], bx + 8, ry, 0x3F3F3F, false);
            // 数值放在右列左半边
            String v = values[i];
            g.drawString(this.font, v, bx + 118 - this.font.width(v), ry, 0x1A3A5F, false);
        }
    }
}
