package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
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
    private int scroll;
    private int lastStateSize = -1;

    private record Row(ResourceLocation id, ItemStack icon, boolean disabled, boolean broken) {
    }

    public RecipeManagerScreen() {
        super(Component.literal("Simple Craft Editor"));
    }

    @Override
    protected void init() {
        rows.clear();
        for (ClientEditorState.Entry entry : ClientEditorState.disabled()) {
            rows.add(new Row(entry.id(), entry.display(), true, entry.broken()));
        }
        for (ClientEditorState.Entry entry : ClientEditorState.generated()) {
            rows.add(new Row(entry.id(), entry.display(), false, false));
        }
        lastStateSize = rows.size();

        addRenderableWidget(Button.builder(Component.literal("New Recipe…"), b ->
                SceNetworking.sendOpenEditor("")).bounds(width / 2 - 155, height - 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 55, height - 30, 100, 20).build());

        int maxRows = Math.max(1, (height - LIST_TOP - 40) / ROW_HEIGHT);
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - maxRows));
        for (int i = 0; i < maxRows && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (row.disabled()) {
                addRenderableWidget(Button.builder(Component.literal("Edit"), b ->
                        SceNetworking.sendOpenEditor(row.id().toString())).bounds(width / 2 + 30, y, 55, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Restore"), b -> {
                    SceNetworking.sendSimple(SceNetworking.ENABLE, row.id());
                    scheduleRefresh();
                }).bounds(width / 2 + 90, y, 70, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                    SceNetworking.sendSimple(SceNetworking.DELETE, row.id());
                    scheduleRefresh();
                }).bounds(width / 2 + 90, y, 70, 20).build());
            }
        }
    }

    private void scheduleRefresh() {
        lastStateSize = -1; // force a rebuild once the server sync arrives
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int currentSize = ClientEditorState.disabled().size() + ClientEditorState.generated().size();
        if (currentSize != lastStateSize) {
            rebuildWidgets();
        }
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);

        int maxRows = Math.max(1, (height - LIST_TOP - 40) / ROW_HEIGHT);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("No disabled or generated recipes yet."),
                    width / 2, LIST_TOP + 10, 0xA0A0A0);
        }
        for (int i = 0; i < maxRows && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            int x = width / 2 - 155;
            graphics.renderItem(row.icon(), x, y);
            int color = row.broken() ? 0xFF6060 : (row.disabled() ? 0xC0C0C0 : 0x90D090);
            String label = row.id().toString() + (row.broken() ? " (unresolved)" : "");
            graphics.drawString(font, label, x + 22, y + 4, color);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxRows = Math.max(1, (height - LIST_TOP - 40) / ROW_HEIGHT);
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, rows.size() - maxRows));
        rebuildWidgets();
        return true;
    }
}
