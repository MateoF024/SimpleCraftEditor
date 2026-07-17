package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
 * Recipe editor on a real synced container: input/output slots and the player inventory behave natively
 * (pick up, place, split, drag, shift-click, hotbar keys), items return on close. The layout matches the
 * recipe type; changing type re-opens the editor. Vanilla types have one output; Create processing types
 * add multiple outputs with per-output drop chance, a processing time and (for mixing) a heat requirement.
 * Tags and prefilled recipes render as ghosts; a physical item overrides its ghost.
 */
@Environment(EnvType.CLIENT)
public class RecipeEditorScreen extends AbstractContainerScreen<RecipeEditorMenu> {
    private static final String[] HEAT_NAMES = {"none", "heated", "superheated"};
    private static final String[] HEAT_LABELS = {"Heat: None", "Heat: Heated", "Heat: Superheated"};

    private final int mode;
    private final int inputCount;
    private final int outputCount;
    private final boolean create;

    private final IngredientValue[] overlay = new IngredientValue[9];
    private final IngredientValue[] overlayOut;
    private final int[] overlayOutCount;
    private final float[] outputChance;

    private int selectedInput = -1;
    private int selectedOutput = -1;
    private String status = "";

    private String idValue;
    private String tagValue = "";
    private float pendingExp = 0.1f;
    private int pendingTime;
    private int heatIndex;

    private EditBox idBox;
    private EditBox tagBox;
    private EditBox expBox;
    private EditBox timeBox;
    private EditBox chanceBox;

    public RecipeEditorScreen(RecipeEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        this.imageHeight = 228;
        this.mode = menu.mode();
        this.inputCount = menu.inputCount();
        this.outputCount = menu.outputCount();
        this.create = RecipeModes.isCreate(mode);
        this.overlayOut = new IngredientValue[outputCount];
        this.overlayOutCount = new int[outputCount];
        this.outputChance = new float[outputCount];
        this.idValue = menu.editId() != null ? menu.editId().toString() : "sce:new_recipe";
        initFromBase(menu.baseDraft());
    }

    private void initFromBase(RecipeDraft base) {
        for (int i = 0; i < 9; i++) {
            overlay[i] = IngredientValue.empty();
        }
        for (int i = 0; i < outputCount; i++) {
            overlayOut[i] = IngredientValue.empty();
            overlayOutCount[i] = 1;
            outputChance[i] = 1.0f;
        }
        if (base == null) {
            return;
        }
        pendingExp = base.experience;
        pendingTime = base.kind == RecipeDraft.Kind.CREATE_PROCESSING ? base.processingTime : base.cookingTime;

        if (base.kind == RecipeDraft.Kind.CRAFTING_SHAPED) {
            for (int row = 0; row < base.height && row < 3; row++) {
                for (int col = 0; col < base.width && col < 3; col++) {
                    overlay[row * 3 + col] = base.input(row * base.width + col);
                }
            }
        } else {
            for (int i = 0; i < base.inputs.size() && i < 9; i++) {
                overlay[i] = base.input(i);
            }
        }

        if (base.kind == RecipeDraft.Kind.CREATE_PROCESSING) {
            for (int i = 0; i < base.results.size() && i < outputCount; i++) {
                RecipeDraft.ResultEntry entry = base.results.get(i);
                overlayOut[i] = entry.item;
                overlayOutCount[i] = entry.count;
                outputChance[i] = entry.chance;
            }
            heatIndex = heatIndexOf(base.heat);
        } else if (outputCount > 0) {
            overlayOut[0] = base.result;
            overlayOutCount[0] = base.resultCount;
        }
    }

    private static int heatIndexOf(String heat) {
        for (int i = 0; i < HEAT_NAMES.length; i++) {
            if (HEAT_NAMES[i].equals(heat)) {
                return i;
            }
        }
        return 0;
    }

    private boolean mixing() {
        return "create:mixing".equals(RecipeModes.createType(mode));
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(Component.literal("Type: " + RecipeModes.label(mode)), b ->
                SceNetworking.sendOpenEditor(idValue, RecipeModes.nextAvailable(mode)))
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
            expBox = new EditBox(font, leftPos + 190, topPos + 42, 42, 16, Component.literal("exp"));
            expBox.setValue(Float.toString(pendingExp));
            expBox.setResponder(s -> pendingExp = parseFloat(s, pendingExp));
            addRenderableWidget(expBox);
            timeBox = new EditBox(font, leftPos + 190, topPos + 66, 42, 16, Component.literal("time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
        }

        if (create) {
            chanceBox = new EditBox(font, leftPos + 188, topPos + 42, 46, 16, Component.literal("chance"));
            chanceBox.setValue(selectedOutput >= 0 ? Float.toString(outputChance[selectedOutput]) : "1.0");
            chanceBox.setResponder(s -> {
                if (selectedOutput >= 0) {
                    outputChance[selectedOutput] = Mth.clamp(parseFloat(s, outputChance[selectedOutput]), 0.0f, 1.0f);
                }
            });
            addRenderableWidget(chanceBox);
            timeBox = new EditBox(font, leftPos + 188, topPos + 66, 46, 16, Component.literal("time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
            if (mixing()) {
                addRenderableWidget(Button.builder(Component.literal(HEAT_LABELS[heatIndex]), b -> {
                    heatIndex = (heatIndex + 1) % HEAT_NAMES.length;
                    rebuildWidgets();
                }).bounds(leftPos + 8, topPos + 88, 120, 16).build());
            }
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
            IngredientValue current = overlay[slotId];
            tagValue = current != null && current.kind() == IngredientValue.Kind.TAG ? current.id().toString() : "";
            if (tagBox != null) {
                tagBox.setValue(tagValue);
            }
        } else if (slotId >= inputCount && slotId < inputCount + outputCount) {
            selectedOutput = slotId - inputCount;
            if (chanceBox != null) {
                chanceBox.setValue(Float.toString(outputChance[selectedOutput]));
            }
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
        draft.id = id;
        draft.width = 3;
        draft.height = 3;
        draft.inputs.clear();
        for (int i = 0; i < inputCount; i++) {
            draft.inputs.add(resolveInput(i));
        }
        if (create) {
            draft.kind = RecipeDraft.Kind.CREATE_PROCESSING;
            draft.createType = RecipeModes.createType(mode);
            draft.processingTime = pendingTime;
            draft.heat = mixing() ? HEAT_NAMES[heatIndex] : "none";
            draft.results.clear();
            for (int i = 0; i < outputCount; i++) {
                IngredientValue item = resolveOutput(i);
                if (!item.isEmpty()) {
                    draft.results.add(new RecipeDraft.ResultEntry(item, resolveOutputCount(i), outputChance[i]));
                }
            }
        } else {
            draft.kind = RecipeModes.kind(mode);
            draft.cooking = RecipeModes.cooking(mode);
            draft.result = resolveOutput(0);
            draft.resultCount = Math.max(1, resolveOutputCount(0));
            if (RecipeModes.isCooking(mode)) {
                draft.experience = pendingExp;
                draft.cookingTime = pendingTime;
            }
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

    private IngredientValue resolveOutput(int index) {
        ItemStack real = menu.outputItem(index);
        if (!real.isEmpty()) {
            return IngredientValue.item(BuiltInRegistries.ITEM.getKey(real.getItem()));
        }
        return overlayOut[index] != null ? overlayOut[index] : IngredientValue.empty();
    }

    private int resolveOutputCount(int index) {
        ItemStack real = menu.outputItem(index);
        return real.isEmpty() ? overlayOutCount[index] : real.getCount();
    }

    // ------------------------------------------------------------------ JEI/EMI ghost-drag hooks

    public int inputSlotCount() {
        return inputCount;
    }

    public int outputSlotCount() {
        return outputCount;
    }

    public Rect2i inputSlotArea(int index) {
        Slot slot = menu.inputSlot(index);
        return new Rect2i(leftPos + slot.x, topPos + slot.y, 16, 16);
    }

    public Rect2i outputSlotArea(int index) {
        Slot slot = menu.outputSlot(index);
        return new Rect2i(leftPos + slot.x, topPos + slot.y, 16, 16);
    }

    public void setGhostInput(int index, ItemStack stack) {
        if (index >= 0 && index < inputCount && !stack.isEmpty()) {
            overlay[index] = IngredientValue.item(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
    }

    public void setGhostOutput(int index, ItemStack stack) {
        if (index >= 0 && index < outputCount && !stack.isEmpty()) {
            overlayOut[index] = IngredientValue.item(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            overlayOutCount[index] = Math.max(1, stack.getCount());
        }
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
        for (int i = 0; i < outputCount; i++) {
            Slot slot = menu.outputSlot(i);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            if (menu.outputItem(i).isEmpty()) {
                drawGhost(graphics, overlayOut[i], x, y, overlayOutCount[i]);
            }
            if (create && i == selectedOutput) {
                graphics.fill(x, y, x + 16, y + 16, 0x4055FF55);
            }
            if (create && outputChance[i] < 1.0f) {
                graphics.drawString(font, "%", x + 1, y + 1, 0xFFD070, false);
            }
        }
        Slot firstOut = menu.outputSlot(0);
        graphics.drawString(font, "->", leftPos + firstOut.x - 16, topPos + firstOut.y + 4, 0xFFFFFF, false);
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
            stack.setCount(Math.max(1, count));
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
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
            graphics.drawString(font, "xp", 174, 46, 0xA0A0A0, false);
            graphics.drawString(font, "time", 166, 70, 0xA0A0A0, false);
        }
        if (create) {
            graphics.drawString(font, "chance", 188, 34, 0xA0A0A0, false);
            graphics.drawString(font, "time", 188, 58, 0xA0A0A0, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 8, imageHeight - 11, 0xE0E070, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            ItemStack stack = hoveredSlot.getItem();
            graphics.renderTooltip(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
            return;
        }
        for (int i = 0; i < inputCount; i++) {
            if (menu.gridItem(i).isEmpty() && contains(inputSlotArea(i), mouseX, mouseY)) {
                ghostTooltip(graphics, overlay[i], mouseX, mouseY);
                return;
            }
        }
        for (int i = 0; i < outputCount; i++) {
            if (menu.outputItem(i).isEmpty() && contains(outputSlotArea(i), mouseX, mouseY)) {
                ghostTooltip(graphics, overlayOut[i], mouseX, mouseY);
                return;
            }
        }
    }

    private boolean contains(Rect2i area, int mouseX, int mouseY) {
        return mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    private void ghostTooltip(GuiGraphics graphics, IngredientValue value, int mouseX, int mouseY) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            graphics.renderTooltip(font, Component.literal("#" + value.id()).withStyle(ChatFormatting.GREEN), mouseX, mouseY);
        } else {
            ItemStack stack = stackFor(value);
            graphics.renderTooltip(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
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
