package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.client.ClientEditorState;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.List;

/**
 * Hub screen: lists disabled recipes (with Restore/Edit) and generated recipes (with Delete), and opens
 * a fresh editor. Disabled recipes are shown here because they are absent from JEI/EMI once disabled.
 */
@Environment(EnvType.CLIENT)
public class RecipeManagerScreen extends BaseSceScreen {
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 44;

    private final List<Row> rows = new ArrayList<>();
    private final StatusLine status = new StatusLine();
    /** The bar down the right of the list: where the list is, and the handle for moving it. */
    private final ScrollBar bar = new ScrollBar();
    private int lastStateSig;

    /**
     * A message left here by the screen that just closed, shown once this one is up.
     *
     * <p>Static because it has to outlive the screen that wrote it: saving hands the confirmation over
     * and then hands the screen over too, and the message has to survive that gap.
     */
    private static Component handover;

    /** Leaves a message for the manager screen that is about to be opened. */
    public static void showOnOpen(Component message) {
        handover = message;
    }

    private record Row(ResourceLocation id, ItemStack icon, boolean disabled, boolean flag, boolean genDisabled) {
    }

    public RecipeManagerScreen() {
        super(Component.translatable("sce.manager.title"));
    }

    @Override
    protected void init() {
        rows.clear();
        for (ClientEditorState.Entry entry : ClientEditorState.disabled()) {
            rows.add(new Row(entry.id(), entry.display(), true, entry.flag(), false));
        }
        for (ClientEditorState.Entry entry : ClientEditorState.generated()) {
            rows.add(new Row(entry.id(), entry.display(), false, entry.flag(), entry.disabled()));
        }
        lastStateSig = stateSignature();
        if (handover != null) {
            status.set(handover);
            handover = null;
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.new_recipe"), b ->
                SceNetworking.sendOpenEditor("", 0)).bounds(width / 2 - 155, height - 30, 100, 20).build());
        // Off to the right, past Done, which belongs where a screen's Done belongs. The rules are part
        // of what this screen is for - what the game does when you put two things together - even
        // though they are not recipes.
        addRenderableWidget(Button.builder(Component.translatable("sce.button.anvil_rules"), b ->
                        minecraft.setScreen(new AnvilRulesScreen(this)))
                .bounds(width / 2 + 55, height - 30, 100, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("sce.tooltip.anvil_rules"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, height - 30, 100, 20).build());

        int maxRows = Math.max(1, (height - LIST_TOP - 40) / ROW_HEIGHT);
        bar.place(width / 2 + 168 + ScrollBar.GAP, LIST_TOP, ROW_HEIGHT, maxRows, rows.size());
        for (int i = 0; i < maxRows && bar.scroll() + i < rows.size(); i++) {
            Row row = rows.get(bar.scroll() + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (row.disabled()) {
                addRenderableWidget(Button.builder(Component.translatable("sce.button.edit"), b ->
                        SceNetworking.sendOpenEditor(row.id().toString(), -1)).bounds(width / 2 + 8, y, 60, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("sce.button.restore"), b ->
                        request(SceNetworking.ENABLE, row.id(), "sce.status.requested_enable"))
                        .bounds(width / 2 + 72, y, 70, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.restore"))).build());
            } else {
                addRenderableWidget(Button.builder(Component.translatable("sce.button.edit"), b ->
                        SceNetworking.sendOpenEditor(row.id().toString(), -1)).bounds(width / 2 + 8, y, 44, 20).build());
                ResourceLocation toggleChannel = row.genDisabled() ? SceNetworking.ENABLE : SceNetworking.DISABLE;
                addRenderableWidget(Button.builder(Component.translatable(row.genDisabled() ? "sce.button.enable" : "sce.button.disable"), b ->
                        request(toggleChannel, row.id(),
                                row.genDisabled() ? "sce.status.requested_enable" : "sce.status.requested_disable"))
                        .bounds(width / 2 + 54, y, 60, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                                row.genDisabled() ? "sce.tooltip.enable" : "sce.tooltip.disable"))).build());
                addRenderableWidget(Button.builder(Component.translatable("sce.button.delete"), b ->
                        request(SceNetworking.DELETE, row.id(), "sce.status.requested_delete"))
                        .bounds(width / 2 + 116, y, 52, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.delete"))).build());
            }
        }
    }

    /**
     * Asks the server for something and says on screen that it was asked for.
     *
     * <p>The answer arrives as a state update that redraws the list, which is the real confirmation; this
     * is for the moment in between, so a click that is still travelling does not look like a click that
     * did nothing.
     */
    private void request(ResourceLocation channel, ResourceLocation id, String messageKey) {
        SceNetworking.sendSimple(channel, id);
        status.set(Component.translatable(messageKey, id.toString()));
    }

    /**
     * A fingerprint of the parts of the editor state the list shows. Rebuilding on a change to this rather
     * than on the row count is what makes a generated recipe's Disable/Enable toggle take effect on the
     * first click: toggling it keeps the same number of rows, so a count check would never notice the flip.
     */
    private int stateSignature() {
        int hash = 1;
        for (ClientEditorState.Entry entry : ClientEditorState.disabled()) {
            hash = 31 * hash + entry.id().hashCode();
            hash = 31 * hash + (entry.flag() ? 1 : 0);
        }
        for (ClientEditorState.Entry entry : ClientEditorState.generated()) {
            hash = 31 * hash + entry.id().hashCode();
            hash = 31 * hash + (entry.flag() ? 2 : 0) + (entry.disabled() ? 1 : 0);
        }
        return hash;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (stateSignature() != lastStateSig) {
            rebuildWidgets();
        }
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        status.drawCentered(graphics, font, width / 2, height - 44);

        int maxRows = Math.max(1, (height - LIST_TOP - 40) / ROW_HEIGHT);
        // The same bar and the same count as the anvil rules. This list scrolled with the wheel and said
        // nothing about it, so forty recipes looked exactly like eleven.
        bar.place(width / 2 + 168 + ScrollBar.GAP, LIST_TOP, ROW_HEIGHT, maxRows, rows.size());
        bar.draw(graphics);
        bar.drawCount(graphics, font, width / 2 + 168, LIST_TOP - 12);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("sce.manager.empty"),
                    width / 2, LIST_TOP + 10, 0xA0A0A0);
        }
        MutableComponent hoverTooltip = null;
        for (int i = 0; i < maxRows && bar.scroll() + i < rows.size(); i++) {
            Row row = rows.get(bar.scroll() + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            int x = width / 2 - 155;
            graphics.renderItem(row.icon(), x, y);
            int color;
            MutableComponent label = Component.literal(row.id().toString());
            if (row.disabled()) {
                color = 0xFF5555; // disabled datapack recipe -> red
                if (row.flag()) {
                    label.append(" ").append(Component.translatable("sce.manager.unresolved"));
                }
            } else if (row.genDisabled()) {
                color = 0xA0A0A0; // generated recipe toggled off -> gray
                label.append(" ").append(Component.translatable("sce.manager.disabled_suffix"));
            } else if (row.flag()) {
                color = 0xFFFF55; // edit of an existing recipe -> yellow
            } else {
                color = 0x55FF55; // brand new recipe -> green
            }
            // Clip the id to the space before the buttons so a long id never runs under them; the full id
            // is shown as a tooltip on hover instead.
            int textX = x + 22;
            int maxTextWidth = width / 2 + 4 - textX;
            String full = label.getString();
            if (font.width(full) > maxTextWidth) {
                graphics.drawString(font, font.plainSubstrByWidth(full, maxTextWidth - font.width("…")) + "…",
                        textX, y + 4, color);
                if (mouseX >= textX && mouseX < width / 2 + 4 && mouseY >= y && mouseY < y + 16) {
                    hoverTooltip = label;
                }
            } else {
                graphics.drawString(font, full, textX, y + 4, color);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hoverTooltip != null) {
            graphics.renderTooltip(font, hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (bar.setScroll(bar.scroll() - (int) Math.signum(delta))) {
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bar.mouseClicked(mouseX, mouseY, button)) {
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (bar.mouseDragged(mouseY)) {
            rebuildWidgets();
        }
        return bar.dragging() || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        bar.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
