package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared helpers for SCE screens, drawing vanilla-styled 16x16 slots with lightweight primitives. */
@Environment(EnvType.CLIENT)
public abstract class BaseSceScreen extends Screen {
    protected BaseSceScreen(Component title) {
        super(title);
    }

    protected void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x, y, x + 16, y + 16, 0xFF373737);
    }

    protected boolean isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    protected boolean isOverSlot(double mouseX, double mouseY, int x, int y) {
        return isOver(mouseX, mouseY, x, y, 16, 16);
    }
}
