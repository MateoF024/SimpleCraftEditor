package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat-style completion for a text box holding an item id. The candidates come straight from the item
 * registry, so whatever the instance actually has loaded — modded included — is offered, and nothing has
 * to be listed ahead of time. There is no inventory or recipe viewer on these screens to drag from, so
 * this is how an id gets typed without knowing it by heart.
 */
@Environment(EnvType.CLIENT)
public final class ItemIdSuggestions {
    private static final int MAX_SHOWN = 7;
    private static final int ROW_HEIGHT = 11;

    private final List<String> matches = new ArrayList<>();
    private EditBox target;

    /** Recomputes the list for whichever box is focused; call once per frame before rendering. */
    public void update(List<EditBox> boxes) {
        EditBox focused = null;
        for (EditBox box : boxes) {
            if (box != null && box.isFocused()) {
                focused = box;
                break;
            }
        }
        if (focused != target) {
            target = focused;
        }
        matches.clear();
        if (target == null) {
            return;
        }
        String typed = target.getValue().trim().toLowerCase();
        if (typed.isEmpty()) {
            return;
        }
        // A leading '#' means the field is naming a tag, which the registry cannot complete.
        if (typed.startsWith("#")) {
            return;
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String text = id.toString();
            if (text.equals(typed)) {
                return; // already an exact id; nothing useful left to suggest
            }
            if (text.contains(typed) || id.getPath().startsWith(typed)) {
                matches.add(text);
                if (matches.size() > MAX_SHOWN) {
                    break;
                }
            }
        }
    }

    public boolean isEmpty() {
        return target == null || matches.isEmpty();
    }

    /** Fills the box with the first match; used for the tab key. */
    public boolean acceptFirst() {
        if (isEmpty()) {
            return false;
        }
        target.setValue(matches.get(0));
        matches.clear();
        return true;
    }

    /** Fills the box with whichever row was clicked. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (isEmpty()) {
            return false;
        }
        int top = popupTop();
        int index = (int) ((mouseY - top) / ROW_HEIGHT);
        if (index < 0 || index >= matches.size() || mouseX < target.getX() || mouseX > target.getX() + width()) {
            return false;
        }
        target.setValue(matches.get(index));
        matches.clear();
        return true;
    }

    public void render(GuiGraphics graphics, Font font) {
        if (isEmpty()) {
            return;
        }
        int x = target.getX();
        int top = popupTop();
        int w = width();
        graphics.fill(x, top, x + w, top + matches.size() * ROW_HEIGHT, 0xF0100010);
        for (int i = 0; i < matches.size(); i++) {
            graphics.drawString(font, matches.get(i), x + 2, top + i * ROW_HEIGHT + 2, 0xC0C0C0, false);
        }
    }

    private int popupTop() {
        return target.getY() + target.getHeight();
    }

    private int width() {
        return Math.max(target.getWidth(), 150);
    }
}
