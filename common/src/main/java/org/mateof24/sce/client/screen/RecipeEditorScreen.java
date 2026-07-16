package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.menu.RecipeEditorMenu;
import org.mateof24.sce.net.SceNetworking;

/**
 * Recipe editor built on a real synced container, so the input slots, the output and the player inventory
 * all behave like a vanilla screen: pick up, place, split, drag, shift-click and hotbar keys work natively,
 * and items are returned to the player on close. The slot layout matches the recipe type (3x3 for crafting,
 * a single input for cooking/stonecutting); changing the type re-opens the editor with the right layout.
 * Tags and prefilled recipes render as ghosts in empty slots; a physical item always overrides its ghost.
 */
@Environment(EnvType.CLIENT)
public class RecipeEditorScreen extends AbstractContainerScreen<RecipeEditorMenu> {
    private final int mode;
    private final int inputCount;

    private final IngredientValue[] overlay = new IngredientValue[9];
    private IngredientValue overlayResult = IngredientValue.empty();
    private int overlayResultCount = 1;

    private int selectedInput = -1;
    private String status = "";

    private String idValue;
    private String tagValue = "";
    private float pendingExp = 0.1f;
    private int pendingTime = 200;

    private EditBox idBox;
    private EditBox tagBox;
    private EditBox expBox;
    private EditBox timeBox;

    public RecipeEditorScreen(RecipeEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        this.imageHeight = 228;
        this.mode = menu.mode();
        this.inputCount = menu.inputCount();
        this.idValue = menu.editId() != null ? menu.editId().toString() : "sce:new_recipe";
        initFromBase(menu.baseDraft());
    }

    private void initFromBase(RecipeDraft base) {
        for (int i = 0; i < 9; i++) {
            overlay[i] = IngredientValue.empty();
        }
        if (base == null) {
            return;
        }
        overlayResult = base.result;
        overlayResultCount = base.resultCount;
        pendingExp = base.experience;
        pendingTime = base.cookingTime;
        if (base.kind == RecipeDraft.Kind.CRAFTING_SHAPED) {
            for (int row = 0; row < base.height && row < 3; row++) {
                for (int col = 0; col < base.width && col < 3; col++) {
                    overlay[row * 3 + col] = base.input(row * base.width + col);
                }
            }
        } else if (base.kind == RecipeDraft.Kind.CRAFTING_SHAPELESS) {
            for (int i = 0; i < base.inputs.size() && i < 9; i++) {
                overlay[i] = base.input(i);
            }
        } else {
            overlay[0] = base.input(0);
        }
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(Component.literal("Type: " + RecipeModes.label(mode)), b ->
                SceNetworking.sendOpenEditor(idValue, RecipeModes.clamp(mode + 1)))
                .bounds(leftPos + 45, topPos + 4, 150, 16).build());

        idBox = new EditBox(font, leftPos + 8, topPos + 22, 180, 16, Component.literal("id"));
        idBox.setMaxLength(200);
        idBox.setValue(idValue);
        idBox.setResponder(s -> idValue = s);
        addRenderableWidget(idBox);
        addRenderableWidget(Button.builder(Component.literal("Load"), b ->
                SceNetworking.sendOpenEditor(idValue, -1)).bounds(leftPos + 192, topPos + 22, 40, 16).build());

        tagBox = new EditBox(font, leftPos + 8, topPos + 98, 98, 16, Component.literal("tag"));
        tagBox.setMaxLength(200);
        tagBox.setValue(tagValue);
        tagBox.setResponder(s -> tagValue = s);
        addRenderableWidget(tagBox);
        addRenderableWidget(Button.builder(Component.literal("Set Tag"), b -> applyTag())
                .bounds(leftPos + 110, topPos + 98, 54, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Slot"), b -> clearSelected())
                .bounds(leftPos + 168, topPos + 98, 64, 16).build());

        if (RecipeModes.isCooking(mode)) {
            expBox = new EditBox(font, leftPos + 180, topPos + 42, 52, 16, Component.literal("exp"));
            expBox.setValue(Float.toString(pendingExp));
            expBox.setResponder(s -> pendingExp = parseFloat(s, pendingExp));
            addRenderableWidget(expBox);
            timeBox = new EditBox(font, leftPos + 180, topPos + 66, 52, 16, Component.literal("time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
        }

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(leftPos + 8, topPos + 204, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Disable"), b -> disable()).bounds(leftPos + 84, topPos + 204, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(leftPos + 160, topPos + 204, 72, 20).build());
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // While an edit box is focused, never let letter keys (E) or number keys act as inventory shortcuts.
        if (getFocused() instanceof EditBox editBox) {
            editBox.keyPressed(key, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slotId >= 0 && slotId < inputCount) {
            selectedInput = slotId;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    private void applyTag() {
        if (selectedInput < 0) {
            status = "Click an input slot first.";
            return;
        }
        if (!menu.gridItem(selectedInput).isEmpty()) {
            status = "Clear the item out of that slot before setting a tag.";
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(tagValue);
        if (tag == null) {
            status = "Invalid tag id.";
            return;
        }
        overlay[selectedInput] = IngredientValue.tag(tag);
    }

    private void clearSelected() {
        if (selectedInput >= 0) {
            overlay[selectedInput] = IngredientValue.empty();
        }
    }

    private void save() {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = "Invalid recipe id.";
            return;
        }
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeModes.kind(mode);
        draft.cooking = RecipeModes.cooking(mode);
        draft.id = id;
        draft.width = 3;
        draft.height = 3;
        draft.inputs.clear();
        for (int i = 0; i < inputCount; i++) {
            draft.inputs.add(resolveInput(i));
        }
        draft.result = resolveResult();
        draft.resultCount = Math.max(1, resolveResultCount());
        if (RecipeModes.isCooking(mode)) {
            draft.experience = pendingExp;
            draft.cookingTime = pendingTime;
        }
        JsonObject json = RecipeCompiler.toJson(draft);
        SceNetworking.sendSave(id, json.toString());
        status = "Saved " + id + ".";
    }

    private void disable() {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = "Invalid recipe id.";
            return;
        }
        SceNetworking.sendSimple(SceNetworking.DISABLE, id);
        status = "Requested disable of " + id + ".";
    }

    private IngredientValue resolveInput(int index) {
        ItemStack real = menu.gridItem(index);
        if (!real.isEmpty()) {
            return IngredientValue.item(BuiltInRegistries.ITEM.getKey(real.getItem()));
        }
        return overlay[index] != null ? overlay[index] : IngredientValue.empty();
    }

    private IngredientValue resolveResult() {
        ItemStack real = menu.outputItem();
        if (!real.isEmpty()) {
            return IngredientValue.item(BuiltInRegistries.ITEM.getKey(real.getItem()));
        }
        return overlayResult;
    }

    private int resolveResultCount() {
        ItemStack real = menu.outputItem();
        return real.isEmpty() ? overlayResultCount : real.getCount();
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xD0101010);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF000000);
        graphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, 0xFF000000);

        for (Slot slot : menu.slots) {
            drawSlot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        for (int i = 0; i < inputCount; i++) {
            Slot slot = menu.inputSlot(i);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            if (menu.gridItem(i).isEmpty()) {
                drawGhost(graphics, overlay[i], x, y, 0);
            }
            if (i == selectedInput) {
                graphics.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
        }
        Slot out = menu.outputSlot();
        if (menu.outputItem().isEmpty()) {
            drawGhost(graphics, overlayResult, leftPos + out.x, topPos + out.y, overlayResultCount);
        }
        graphics.drawString(font, "->", leftPos + out.x - 16, topPos + out.y + 4, 0xFFFFFF, false);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x, y, x + 16, y + 16, 0xFF373737);
    }

    private void drawGhost(GuiGraphics graphics, IngredientValue value, int x, int y, int count) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ItemStack stack = stackFor(value);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.fill(x, y, x + 16, y + 16, 0x66101010);
            if (count > 1) {
                graphics.drawString(font, Integer.toString(count), x + 11, y + 9, 0xC0C0C0, true);
            }
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            graphics.drawString(font, "#", x + 1, y + 1, 0x55FF55, false);
        }
    }

    private static ItemStack stackFor(IngredientValue value) {
        if (value.kind() == IngredientValue.Kind.TAG) {
            return new ItemStack(Items.NAME_TAG);
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(value.id()));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "tag:", 8, 88, 0xA0A0A0, false);
        if (RecipeModes.isCooking(mode)) {
            graphics.drawString(font, "xp", 164, 46, 0xA0A0A0, false);
            graphics.drawString(font, "time", 156, 70, 0xA0A0A0, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 8, imageHeight - 11, 0xE0E070, false);
        }
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
