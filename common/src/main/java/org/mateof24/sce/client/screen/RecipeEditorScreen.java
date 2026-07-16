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
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.menu.RecipeEditorMenu;
import org.mateof24.sce.net.SceNetworking;

/**
 * Recipe editor built on a real synced container, so the input grid, the output and the player inventory
 * all behave like a vanilla screen: pick up, place, split, drag, shift-click and hotbar keys work natively,
 * and items are returned to the player on close. Tags and prefilled (edited) recipes render as ghosts in
 * empty slots; a physical item in a slot always overrides its ghost.
 */
@Environment(EnvType.CLIENT)
public class RecipeEditorScreen extends AbstractContainerScreen<RecipeEditorMenu> {
    private static final RecipeDraft.Kind[] MODE_KIND = {
            RecipeDraft.Kind.CRAFTING_SHAPELESS, RecipeDraft.Kind.CRAFTING_SHAPED,
            RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING,
            RecipeDraft.Kind.STONECUTTING};
    private static final RecipeDraft.Cooking[] MODE_COOK = {
            null, null, RecipeDraft.Cooking.SMELTING, RecipeDraft.Cooking.BLASTING,
            RecipeDraft.Cooking.SMOKING, RecipeDraft.Cooking.CAMPFIRE, null};
    private static final String[] MODE_LABEL = {
            "Shapeless", "Shaped", "Smelting", "Blasting", "Smoking", "Campfire", "Stonecutting"};

    private final IngredientValue[] overlay = new IngredientValue[9];
    private IngredientValue overlayResult = IngredientValue.empty();
    private int overlayResultCount = 1;

    private int modeIndex;
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
        this.imageWidth = 210;
        this.imageHeight = 228;
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
        modeIndex = modeIndexOf(base);
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

    private static int modeIndexOf(RecipeDraft base) {
        for (int i = 0; i < MODE_KIND.length; i++) {
            if (MODE_KIND[i] == base.kind && (MODE_COOK[i] == null || MODE_COOK[i] == base.cooking)) {
                return i;
            }
        }
        return 0;
    }

    private boolean cooking() {
        return MODE_KIND[modeIndex] == RecipeDraft.Kind.COOKING;
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(Component.literal("Type: " + MODE_LABEL[modeIndex]), b -> {
            modeIndex = (modeIndex + 1) % MODE_KIND.length;
            rebuildWidgets();
        }).bounds(leftPos + 30, topPos + 4, 150, 16).build());

        idBox = new EditBox(font, leftPos + 8, topPos + 22, 150, 16, Component.literal("id"));
        idBox.setMaxLength(200);
        idBox.setValue(idValue);
        idBox.setResponder(s -> idValue = s);
        addRenderableWidget(idBox);

        tagBox = new EditBox(font, leftPos + 8, topPos + 98, 104, 16, Component.literal("tag"));
        tagBox.setMaxLength(200);
        tagBox.setValue(tagValue);
        tagBox.setResponder(s -> tagValue = s);
        addRenderableWidget(tagBox);
        addRenderableWidget(Button.builder(Component.literal("Set Tag"), b -> applyTag())
                .bounds(leftPos + 116, topPos + 98, 52, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Clr"), b -> clearSelected())
                .bounds(leftPos + 170, topPos + 98, 32, 16).build());

        if (cooking()) {
            expBox = new EditBox(font, leftPos + 150, topPos + 42, 52, 16, Component.literal("exp"));
            expBox.setValue(Float.toString(pendingExp));
            expBox.setResponder(s -> pendingExp = parseFloat(s, pendingExp));
            addRenderableWidget(expBox);
            timeBox = new EditBox(font, leftPos + 150, topPos + 64, 52, 16, Component.literal("time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
        }

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(leftPos + 8, topPos + 204, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Disable"), b -> disable()).bounds(leftPos + 74, topPos + 204, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(leftPos + 142, topPos + 204, 60, 20).build());
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slotId >= 0 && slotId < RecipeEditorMenu.GRID_SIZE) {
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
        draft.kind = MODE_KIND[modeIndex];
        draft.cooking = MODE_COOK[modeIndex] != null ? MODE_COOK[modeIndex] : RecipeDraft.Cooking.SMELTING;
        draft.id = id;
        draft.width = 3;
        draft.height = 3;
        draft.inputs.clear();
        for (int i = 0; i < 9; i++) {
            draft.inputs.add(resolveInput(i));
        }
        draft.result = resolveResult();
        draft.resultCount = Math.max(1, resolveResultCount());
        if (cooking()) {
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
        for (int i = 0; i < 9; i++) {
            if (menu.gridItem(i).isEmpty()) {
                drawGhost(graphics, overlay[i], leftPos + 34 + (i % 3) * 18, topPos + 42 + (i / 3) * 18, 0);
            }
            if (i == selectedInput) {
                int x = leftPos + 34 + (i % 3) * 18;
                int y = topPos + 42 + (i / 3) * 18;
                graphics.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
        }
        if (menu.outputItem().isEmpty()) {
            drawGhost(graphics, overlayResult, leftPos + 128, topPos + 60, overlayResultCount);
        }
        graphics.drawString(font, "->", leftPos + 110, topPos + 64, 0xFFFFFF, false);
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
        if (cooking()) {
            graphics.drawString(font, "xp", 134, 46, 0xA0A0A0, false);
            graphics.drawString(font, "time", 128, 68, 0xA0A0A0, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 8, imageHeight - 10, 0xE0E070, false);
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
