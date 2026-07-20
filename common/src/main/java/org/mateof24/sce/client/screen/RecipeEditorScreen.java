package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import org.mateof24.sce.client.ClientEditorState;
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

    private static final ResourceLocation BG_TEXTURE = new ResourceLocation("sce", "textures/gui/sce_bg.png");
    private static final ResourceLocation SLOT_TEXTURE = new ResourceLocation("sce", "textures/gui/sce_slot.png");
    private static final ResourceLocation ARROW_TEXTURE = new ResourceLocation("sce", "textures/gui/sce_arrow.png");

    // Carries the cursor position across a menu re-open so it isn't recentered (see reopen/init).
    private static double pendingCursorX = -1.0;
    private static double pendingCursorY = -1.0;

    private final int mode;
    private final int inputCount;
    private final int outputCount;
    private final boolean create;
    private final boolean mechanical;
    /** Mechanical crafting only: whether Create should also match the pattern mirrored. */
    private boolean mirrored;

    private final IngredientValue[] overlay =
            new IngredientValue[RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE];
    private final IngredientValue[] overlayOut;
    private final int[] overlayOutCount;
    private final float[] outputChance;

    private int selectedInput = -1;
    private int selectedOutput = -1;
    /** Whether the slot picked last was an output, so the fluid row knows which one to write to. */
    private boolean selectedIsOutput;

    private EditBox fluidBox;
    private EditBox fluidAmountBox;
    private String fluidValue = "";
    private String fluidAmountValue = Integer.toString(IngredientValue.BUCKET);
    private Component status = Component.empty();

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
        this.imageHeight = 266;
        this.mode = menu.mode();
        this.inputCount = menu.inputCount();
        this.outputCount = menu.outputCount();
        this.create = RecipeModes.isCreate(mode);
        this.mechanical = RecipeModes.isMechanicalCrafting(mode);
        this.overlayOut = new IngredientValue[outputCount];
        this.overlayOutCount = new int[outputCount];
        this.outputChance = new float[outputCount];
        this.idValue = menu.editId() != null ? menu.editId().toString() : "sce:new_recipe";
        initFromBase(menu.baseDraft());
    }

    private void initFromBase(RecipeDraft base) {
        for (int i = 0; i < overlay.length; i++) {
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

        if (base.kind == RecipeDraft.Kind.CRAFTING_SHAPED || base.kind == RecipeDraft.Kind.MECHANICAL_CRAFTING) {
            // Row-major grids: re-index the recipe's own width onto the width this editor shows, clipping
            // anything larger than the grid we can display.
            int columns = gridColumns();
            for (int row = 0; row < base.height && row < columns; row++) {
                for (int col = 0; col < base.width && col < columns; col++) {
                    overlay[row * columns + col] = base.input(row * base.width + col);
                }
            }
            mirrored = base.acceptMirrored;
        } else {
            for (int i = 0; i < base.inputs.size() && i < overlay.length; i++) {
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

    /** Columns in the input grid; mechanical crafting uses a bigger square than the vanilla 3x3. */
    private int gridColumns() {
        return mechanical ? RecipeDraft.MECHANICAL_SIZE : 3;
    }

    private boolean mixing() {
        return "create:mixing".equals(RecipeModes.createType(mode));
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(Component.translatable("sce.button.type", Component.translatable(RecipeModes.labelKey(mode))), b ->
                reopen(RecipeModes.nextAvailable(mode)))
                .bounds(leftPos + 45, topPos + 4, 150, 16).build());

        idBox = new EditBox(font, leftPos + 8, topPos + 22, 180, 16, Component.translatable("sce.hint.id"));
        idBox.setMaxLength(200);
        idBox.setValue(idValue);
        idBox.setResponder(s -> idValue = s);
        addRenderableWidget(idBox);
        addRenderableWidget(Button.builder(Component.translatable("sce.button.load"), b -> reopen(-1))
                .bounds(leftPos + 192, topPos + 22, 40, 16).build());

        // Mechanical crafting's grid is taller, so its tag row sits below it.
        int tagRowY = mechanical ? 136 : 98;
        tagBox = new EditBox(font, leftPos + 8, topPos + tagRowY, 98, 16, Component.translatable("sce.hint.tag"));
        tagBox.setMaxLength(200);
        tagBox.setValue(tagValue);
        tagBox.setHint(Component.translatable("sce.hint.tag_id"));
        tagBox.setResponder(s -> tagValue = s);
        addRenderableWidget(tagBox);
        addRenderableWidget(Button.builder(Component.translatable("sce.button.set_tag"), b -> applyTag())
                .bounds(leftPos + 110, topPos + tagRowY, 54, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.clear_slot"), b -> clearSelected())
                .bounds(leftPos + 168, topPos + tagRowY, 64, 16).build());

        if (mechanical) {
            // Sits in the free space beside the grid, under the result slot.
            addRenderableWidget(Button.builder(Component.translatable("sce.button.mirrored",
                    Component.translatable(mirrored ? "sce.toggle.on" : "sce.toggle.off")), b -> {
                mirrored = !mirrored;
                rebuildWidgets();
            }).bounds(leftPos + 148, topPos + 100, 84, 16).build());
        }

        if (RecipeModes.isCooking(mode)) {
            expBox = new EditBox(font, leftPos + 190, topPos + 42, 42, 16, Component.translatable("sce.hint.exp"));
            expBox.setValue(Float.toString(pendingExp));
            expBox.setResponder(s -> pendingExp = parseFloat(s, pendingExp));
            addRenderableWidget(expBox);
            timeBox = new EditBox(font, leftPos + 190, topPos + 66, 42, 16, Component.translatable("sce.hint.time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
        }

        if (create) {
            // Create's grid is only two rows tall, so its extra controls sit in the free row below it.
            chanceBox = new EditBox(font, leftPos + 52, topPos + 80, 36, 16, Component.translatable("sce.hint.chance"));
            chanceBox.setValue(selectedOutput >= 0 ? Float.toString(outputChance[selectedOutput]) : "1.0");
            chanceBox.setResponder(s -> {
                if (selectedOutput >= 0) {
                    outputChance[selectedOutput] = Mth.clamp(parseFloat(s, outputChance[selectedOutput]), 0.0f, 1.0f);
                }
            });
            addRenderableWidget(chanceBox);
            timeBox = new EditBox(font, leftPos + 124, topPos + 80, 36, 16, Component.translatable("sce.hint.time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            addRenderableWidget(timeBox);
            if (mixing()) {
                addRenderableWidget(Button.builder(Component.translatable("sce.button.heat", Component.translatable("sce.heat." + HEAT_NAMES[heatIndex])), b -> {
                    heatIndex = (heatIndex + 1) % HEAT_NAMES.length;
                    rebuildWidgets();
                }).bounds(leftPos + 164, topPos + 80, 68, 16).build());
            }

            // Only Create takes fluids, and it takes them as an amount rather than as a bucket item.
            fluidBox = new EditBox(font, leftPos + 8, topPos + 118, 110, 16, Component.translatable("sce.hint.fluid"));
            fluidBox.setMaxLength(200);
            fluidBox.setValue(fluidValue);
            fluidBox.setHint(Component.translatable("sce.hint.fluid_id"));
            fluidBox.setResponder(s -> fluidValue = s);
            addRenderableWidget(fluidBox);
            fluidAmountBox = new EditBox(font, leftPos + 122, topPos + 118, 40, 16, Component.translatable("sce.hint.amount"));
            fluidAmountBox.setValue(fluidAmountValue);
            fluidAmountBox.setResponder(s -> fluidAmountValue = s);
            addRenderableWidget(fluidAmountBox);
            addRenderableWidget(Button.builder(Component.translatable("sce.button.set_fluid"), b -> applyFluid())
                    .bounds(leftPos + 166, topPos + 118, 66, 16).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.save"), b -> save()).bounds(leftPos + 8, topPos + 238,52, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.disable"), b -> disable()).bounds(leftPos + 64, topPos + 238,58, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.raw"), b -> openRaw()).bounds(leftPos + 126, topPos + 238,40, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.close"), b -> onClose()).bounds(leftPos + 170, topPos + 238,62, 20).build());

        // Opening a new menu recenters the cursor (the client briefly returns to the world in between);
        // put it back where it was so cycling the type/loading doesn't yank the mouse to the middle.
        if (pendingCursorX >= 0.0) {
            double cursorX = pendingCursorX;
            double cursorY = pendingCursorY;
            pendingCursorX = -1.0;
            pendingCursorY = -1.0;
            GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), cursorX, cursorY);
        }
    }

    /**
     * Remembers where the pointer is so the editor's next open puts it back instead of letting the menu
     * transition recenter it. Call this before asking the server to open the editor — from the type/load
     * buttons here, and from the editor key, which would otherwise fling the pointer to the middle of the
     * screen on every press while stepping through an item's recipes.
     */
    public static void rememberCursor() {
        Minecraft minecraft = Minecraft.getInstance();
        pendingCursorX = minecraft.mouseHandler.xpos();
        pendingCursorY = minecraft.mouseHandler.ypos();
    }

    /** Re-open the editor for a new type/recipe, preserving the cursor position across the transition. */
    private void reopen(int newMode) {
        rememberCursor();
        SceNetworking.sendOpenEditor(idValue, newMode);
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
            selectedIsOutput = false;
            IngredientValue current = overlay[slotId];
            tagValue = current != null && current.kind() == IngredientValue.Kind.TAG ? current.id().toString() : "";
            if (tagBox != null) {
                tagBox.setValue(tagValue);
            }
            syncFluidBoxes(current);
        } else if (slotId >= inputCount && slotId < inputCount + outputCount) {
            selectedOutput = slotId - inputCount;
            selectedIsOutput = true;
            if (chanceBox != null) {
                chanceBox.setValue(Float.toString(outputChance[selectedOutput]));
            }
            syncFluidBoxes(overlayOut[selectedOutput]);
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    /** Mirrors the picked slot's fluid, if it holds one, into the fluid row so it can be adjusted. */
    private void syncFluidBoxes(IngredientValue current) {
        if (fluidBox == null) {
            return;
        }
        if (current != null && current.isFluid()) {
            fluidValue = current.id().toString();
            fluidAmountValue = Integer.toString(current.amount());
        } else {
            fluidValue = "";
        }
        fluidBox.setValue(fluidValue);
        if (fluidAmountBox != null) {
            fluidAmountBox.setValue(fluidAmountValue);
        }
    }

    private void applyTag() {
        if (selectedInput < 0) {
            status = Component.translatable("sce.status.click_input_first");
            return;
        }
        if (!menu.gridItem(selectedInput).isEmpty()) {
            status = Component.translatable("sce.status.clear_slot_first");
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(tagValue);
        if (tag == null) {
            status = Component.translatable("sce.status.invalid_tag");
            return;
        }
        overlay[selectedInput] = IngredientValue.tag(tag);
    }

    /** Puts a fluid, with its millibucket amount, into whichever recipe slot was picked last. */
    private void applyFluid() {
        ResourceLocation fluid = ResourceLocation.tryParse(fluidValue);
        if (fluid == null) {
            status = Component.translatable("sce.status.invalid_fluid");
            return;
        }
        int amount = Math.max(1, parseInt(fluidAmountValue, IngredientValue.BUCKET));
        if (selectedIsOutput && selectedOutput >= 0) {
            if (!menu.outputItem(selectedOutput).isEmpty()) {
                status = Component.translatable("sce.status.clear_slot_first");
                return;
            }
            overlayOut[selectedOutput] = IngredientValue.fluid(fluid, amount);
            return;
        }
        if (selectedInput < 0) {
            status = Component.translatable("sce.status.click_slot_first");
            return;
        }
        if (!menu.gridItem(selectedInput).isEmpty()) {
            status = Component.translatable("sce.status.clear_slot_first");
            return;
        }
        overlay[selectedInput] = IngredientValue.fluid(fluid, amount);
    }

    private void clearSelected() {
        if (selectedIsOutput && selectedOutput >= 0) {
            overlayOut[selectedOutput] = IngredientValue.empty();
        } else if (selectedInput >= 0) {
            overlay[selectedInput] = IngredientValue.empty();
        }
    }

    private RecipeDraft buildDraft(ResourceLocation id) {
        RecipeDraft draft = new RecipeDraft();
        draft.id = id;
        draft.width = gridColumns();
        draft.height = gridColumns();
        draft.acceptMirrored = mirrored;
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
        return draft;
    }

    private void save() {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = Component.translatable("sce.status.invalid_id");
            return;
        }
        SceNetworking.sendSave(id, RecipeCompiler.toJson(buildDraft(id)).toString());
        status = Component.translatable("sce.status.saving", id.toString());
    }

    /** Called from the network layer with the server's verdict on a save request. */
    public void onSaveResult(ResourceLocation id, boolean ok) {
        status = Component.translatable(ok ? "sce.status.saved" : "sce.status.save_failed", id.toString());
    }

    /** Opens the raw-JSON view for this recipe: the server's stored JSON if any, else the current draft. */
    private void openRaw() {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = Component.translatable("sce.status.invalid_id");
            return;
        }
        ClientEditorState.requestJson(id, json -> {
            String text = json != null ? json.toString() : RecipeCompiler.toJson(buildDraft(id)).toString();
            minecraft.setScreen(new RawRecipeScreen(id, text));
        });
    }

    private void disable() {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = Component.translatable("sce.status.invalid_id");
            return;
        }
        SceNetworking.sendSimple(SceNetworking.DISABLE, id);
        status = Component.translatable("sce.status.requested_disable", id.toString());
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
        // Place a real item into the slot so it behaves like a normal container (pick up, drag, …).
        if (index >= 0 && index < inputCount && !stack.isEmpty()) {
            SceNetworking.sendSetSlot(index, stack.copy());
        }
    }

    public void setGhostOutput(int index, ItemStack stack) {
        if (index >= 0 && index < outputCount && !stack.isEmpty()) {
            SceNetworking.sendSetSlot(inputCount + index, stack.copy());
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BG_TEXTURE, leftPos, topPos, imageWidth, imageHeight, 0.0F, 0.0F, imageWidth, imageHeight, imageWidth, imageHeight);

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
                graphics.fill(x, y, x + 16, y + 16, 0x600060C0);
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
                graphics.fill(x, y, x + 16, y + 16, 0x6020A020);
            }
            if (create && outputChance[i] < 1.0f) {
                graphics.drawString(font, "%", x + 1, y + 1, 0xB07000, false);
            }
        }
        Slot firstOut = menu.outputSlot(0);
        int inputRight = 0;
        for (int i = 0; i < inputCount; i++) {
            inputRight = Math.max(inputRight, menu.inputSlot(i).x + 16);
        }
        int arrowX = leftPos + (inputRight + firstOut.x) / 2 - 11;
        int outBottom = menu.outputSlot(outputCount - 1).y + 16;
        int arrowY = topPos + (firstOut.y + outBottom) / 2 - 8;
        graphics.blit(ARROW_TEXTURE, arrowX, arrowY, 22, 15, 0.0F, 0.0F, 22, 15, 22, 15);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.blit(SLOT_TEXTURE, x - 1, y - 1, 18, 18, 0.0F, 0.0F, 18, 18, 18, 18);
    }

    private void drawGhost(GuiGraphics graphics, IngredientValue value, int x, int y, int count) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ItemStack stack = stackFor(value);
        if (!stack.isEmpty()) {
            stack.setCount(Math.max(1, count));
            // Ghosts are only a preview of the current definition, so draw them at 70% opacity.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            graphics.setColor(1.0F, 1.0F, 1.0F, 0.7F);
            graphics.renderItem(stack, x, y);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            graphics.renderItemDecorations(font, stack, x, y);
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            graphics.drawString(font, "#", x + 1, y + 1, 0x55FF55, false);
        } else if (value.isFluid()) {
            // A fluid is a quantity rather than an item, so mark the slot and show how much it is.
            graphics.drawString(font, "~", x + 1, y + 1, 0x55AAFF, false);
            graphics.pose().pushPose();
            graphics.pose().translate(x, y + 10, 200.0F);
            graphics.pose().scale(0.5F, 0.5F, 1.0F);
            graphics.drawString(font, shortAmount(value.amount()), 0, 0, 0x9CDCFF, false);
            graphics.pose().popPose();
        }
    }

    /** Compact millibucket label: 1000 mB shows as "1B", anything else as its mB count. */
    private static String shortAmount(int millibuckets) {
        return millibuckets % IngredientValue.BUCKET == 0
                ? (millibuckets / IngredientValue.BUCKET) + "B"
                : millibuckets + "mb";
    }

    private static ItemStack stackFor(IngredientValue value) {
        if (value.kind() == IngredientValue.Kind.TAG) {
            return new ItemStack(Items.NAME_TAG);
        }
        if (value.isFluid()) {
            return new ItemStack(Items.BUCKET); // stand-in icon; the fluid id is in the tooltip
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(value.id()));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (RecipeModes.isCooking(mode)) {
            graphics.drawString(font, Component.translatable("sce.label.xp"), 174, 46, 0x404040, false);
            graphics.drawString(font, Component.translatable("sce.label.time"), 166, 70, 0x404040, false);
        }
        if (create) {
            graphics.drawString(font, Component.translatable("sce.label.chance"), 8, 84, 0x404040, false);
            graphics.drawString(font, Component.translatable("sce.label.time"), 96, 84, 0x404040, false);
        }
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, imageWidth / 2, imageHeight + 4, 0xE0E070);
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
        } else if (value.isFluid()) {
            graphics.renderTooltip(font, Component.literal(value.id() + " (" + value.amount() + " mB)")
                    .withStyle(ChatFormatting.AQUA), mouseX, mouseY);
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
