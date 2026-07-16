package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.mateof24.sce.client.ClientEditorState;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.net.SceNetworking;

/**
 * Our own recipe editor. Independent of JEI/EMI: it shows the recipe's slots plus the player's inventory
 * so ingredients can be picked straight from it. Left-click an inventory item to hold it, then left-click a
 * recipe slot to place it; right-click a slot to clear it. Tags are applied via the tag field.
 */
@Environment(EnvType.CLIENT)
public class RecipeEditorScreen extends BaseSceScreen {
    private RecipeDraft draft;
    private final ResourceLocation loadId;
    private boolean requested;

    private ItemStack cursor = ItemStack.EMPTY;
    private int selectedInput = -1;
    private String status = "";

    private EditBox idBox;
    private EditBox tagBox;
    private EditBox expBox;
    private EditBox timeBox;

    private int gridX;
    private int gridY;

    private RecipeEditorScreen(RecipeDraft draft, ResourceLocation loadId) {
        super(Component.literal("Recipe Editor"));
        this.draft = draft;
        this.loadId = loadId;
        if (loadId != null) {
            this.draft.id = loadId;
        }
    }

    public static RecipeEditorScreen forNew() {
        return new RecipeEditorScreen(RecipeDraft.blank(RecipeDraft.Kind.CRAFTING_SHAPELESS), null);
    }

    public static RecipeEditorScreen forExisting(ResourceLocation id) {
        return new RecipeEditorScreen(RecipeDraft.blank(RecipeDraft.Kind.CRAFTING_SHAPELESS), id);
    }

    @Override
    protected void init() {
        if (loadId != null && !requested) {
            requested = true;
            ClientEditorState.requestJson(loadId, json -> onLoaded(loadId, json));
        }

        gridX = width / 2 - 110;
        gridY = 56;

        idBox = new EditBox(font, width / 2 - 110, 26, 180, 18, Component.literal("id"));
        idBox.setMaxLength(200);
        idBox.setValue(draft.id != null ? draft.id.toString() : "sce:new_recipe");
        addRenderableWidget(idBox);
        addRenderableWidget(Button.builder(Component.literal("Load"), b -> loadFromId())
                .bounds(width / 2 + 74, 26, 40, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Type: " + kindLabel()), b -> cycleKind())
                .bounds(width / 2 - 110, 6, 150, 16).build());

        int rightX = gridX + 150;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustCount(-1)).bounds(rightX, gridY + 42, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustCount(1)).bounds(rightX + 40, gridY + 42, 16, 16).build());

        tagBox = new EditBox(font, gridX, gridY + 70, 150, 16, Component.literal("tag"));
        tagBox.setMaxLength(200);
        tagBox.setValue("");
        addRenderableWidget(tagBox);
        addRenderableWidget(Button.builder(Component.literal("Set Tag"), b -> applyTag()).bounds(gridX + 154, gridY + 70, 56, 16).build());

        if (draft.kind == RecipeDraft.Kind.COOKING) {
            expBox = new EditBox(font, gridX, gridY + 92, 70, 16, Component.literal("exp"));
            expBox.setValue(Float.toString(draft.experience));
            addRenderableWidget(expBox);
            timeBox = new EditBox(font, gridX + 90, gridY + 92, 70, 16, Component.literal("time"));
            timeBox.setValue(Integer.toString(draft.cookingTime));
            addRenderableWidget(timeBox);
        }

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width / 2 - 154, height - 28, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Disable"), b -> disable()).bounds(width / 2 - 60, height - 28, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 64, height - 28, 90, 20).build());
    }

    private void onLoaded(ResourceLocation id, JsonObject json) {
        if (json == null) {
            status = "No JSON for " + id + " (special or missing recipe).";
            return;
        }
        RecipeDraft loaded = RecipeCompiler.fromJson(id, json);
        if (loaded == null) {
            status = "Recipe type of " + id + " is not editable yet.";
            return;
        }
        this.draft = loaded;
        rebuildWidgets();
    }

    private void loadFromId() {
        ResourceLocation id = ResourceLocation.tryParse(idBox.getValue());
        if (id == null) {
            status = "Invalid recipe id.";
            return;
        }
        ClientEditorState.requestJson(id, json -> onLoaded(id, json));
    }

    private String kindLabel() {
        return switch (draft.kind) {
            case CRAFTING_SHAPELESS -> "Shapeless";
            case CRAFTING_SHAPED -> "Shaped";
            case COOKING -> "Cooking (" + draft.cooking.name().toLowerCase() + ")";
            case STONECUTTING -> "Stonecutting";
        };
    }

    private void cycleKind() {
        RecipeDraft.Kind[] kinds = RecipeDraft.Kind.values();
        RecipeDraft.Kind next = kinds[(draft.kind.ordinal() + 1) % kinds.length];
        ResourceLocation keepId = ResourceLocation.tryParse(idBox.getValue());
        draft = RecipeDraft.blank(next);
        draft.id = keepId;
        selectedInput = -1;
        rebuildWidgets();
    }

    private void adjustCount(int delta) {
        draft.resultCount = Mth.clamp(draft.resultCount + delta, 1, 64);
    }

    private void applyTag() {
        if (selectedInput < 0) {
            status = "Select an input slot first, then Set Tag.";
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(tagBox.getValue());
        if (tag == null) {
            status = "Invalid tag id.";
            return;
        }
        draft.setInput(selectedInput, IngredientValue.tag(tag));
    }

    private void save() {
        ResourceLocation id = ResourceLocation.tryParse(idBox.getValue());
        if (id == null) {
            status = "Invalid recipe id.";
            return;
        }
        draft.id = id;
        if (draft.kind == RecipeDraft.Kind.COOKING) {
            draft.experience = parseFloat(expBox.getValue(), draft.experience);
            draft.cookingTime = parseInt(timeBox.getValue(), draft.cookingTime);
        }
        JsonObject json = RecipeCompiler.toJson(draft);
        SceNetworking.sendSave(id, json.toString());
        status = "Sent recipe " + id + " to the server.";
    }

    private void disable() {
        ResourceLocation id = ResourceLocation.tryParse(idBox.getValue());
        if (id == null) {
            status = "Invalid recipe id.";
            return;
        }
        SceNetworking.sendSimple(SceNetworking.DISABLE, id);
        status = "Requested disable of " + id + ".";
    }

    // ------------------------------------------------------------------ layout helpers

    private int inputSlotCount() {
        return switch (draft.kind) {
            case CRAFTING_SHAPED, CRAFTING_SHAPELESS -> 9;
            case COOKING, STONECUTTING -> 1;
        };
    }

    private int inputSlotX(int index) {
        if (draft.kind == RecipeDraft.Kind.COOKING || draft.kind == RecipeDraft.Kind.STONECUTTING) {
            return gridX;
        }
        return gridX + (index % 3) * 20;
    }

    private int inputSlotY(int index) {
        if (draft.kind == RecipeDraft.Kind.COOKING || draft.kind == RecipeDraft.Kind.STONECUTTING) {
            return gridY + 20;
        }
        return gridY + (index / 3) * 20;
    }

    private int outputX() {
        return gridX + 110;
    }

    private int outputY() {
        return gridY + 20;
    }

    private int invX() {
        return width / 2 - (9 * 18) / 2;
    }

    private int invY() {
        return height - 96;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < inputSlotCount(); i++) {
            if (isOverSlot(mouseX, mouseY, inputSlotX(i), inputSlotY(i))) {
                selectedInput = i;
                if (button == 1) {
                    draft.setInput(i, IngredientValue.empty());
                } else {
                    draft.setInput(i, cursor.isEmpty() ? IngredientValue.empty() : IngredientValue.item(idOf(cursor)));
                }
                return true;
            }
        }
        if (isOverSlot(mouseX, mouseY, outputX(), outputY())) {
            if (button == 1) {
                draft.result = IngredientValue.empty();
            } else if (!cursor.isEmpty()) {
                draft.result = IngredientValue.item(idOf(cursor));
            }
            return true;
        }
        for (int i = 0; i < 36; i++) {
            int x = invX() + (i % 9) * 18;
            int y = invY() + (i / 9) * 18;
            if (isOverSlot(mouseX, mouseY, x, y)) {
                ItemStack stack = minecraft.player.getInventory().getItem(i);
                cursor = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static ResourceLocation idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        for (int i = 0; i < inputSlotCount(); i++) {
            int x = inputSlotX(i);
            int y = inputSlotY(i);
            drawSlot(graphics, x, y);
            if (i == selectedInput) {
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x60FFFFFF);
            }
            drawIngredient(graphics, draft.input(i), x, y);
        }

        drawSlot(graphics, outputX(), outputY());
        drawResult(graphics, outputX(), outputY());
        graphics.drawString(font, "->", outputX() - 16, outputY() + 4, 0xFFFFFF);
        graphics.drawString(font, "x" + draft.resultCount, gridX + 168, gridY + 46, 0xFFFFFF);

        graphics.drawString(font, "Inventory (click to hold):", invX(), invY() - 12, 0xA0A0A0);
        for (int i = 0; i < 36; i++) {
            int x = invX() + (i % 9) * 18;
            int y = invY() + (i / 9) * 18;
            drawSlot(graphics, x, y);
            ItemStack stack = minecraft.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
                graphics.renderItemDecorations(font, stack, x, y);
            }
        }

        if (!status.isEmpty()) {
            graphics.drawString(font, status, width / 2 - 154, height - 42, 0xE0E070);
        }

        if (!cursor.isEmpty()) {
            graphics.renderItem(cursor, mouseX - 8, mouseY - 8);
        }
    }

    private void drawIngredient(GuiGraphics graphics, IngredientValue value, int x, int y) {
        ItemStack stack = stackFor(value);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            graphics.drawString(font, "#", x + 1, y + 1, 0x55FF55);
        }
    }

    private void drawResult(GuiGraphics graphics, int x, int y) {
        ItemStack stack = stackFor(draft.result);
        if (!stack.isEmpty()) {
            stack.setCount(Math.max(1, draft.resultCount));
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
        }
    }

    private static ItemStack stackFor(IngredientValue value) {
        if (value.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            return new ItemStack(Items.NAME_TAG);
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(value.id()));
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
