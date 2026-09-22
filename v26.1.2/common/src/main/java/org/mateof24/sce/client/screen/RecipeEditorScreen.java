package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.client.ClientEditorState;
import org.mateof24.sce.client.CobblemonSeasonings;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.world.item.Item;
import org.mateof24.sce.core.edit.CookingPot;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.recipe.InheritingCraftingRecipe;
import org.mateof24.sce.menu.EditorLayout;
import org.mateof24.sce.menu.RecipeEditorMenu;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * Where the Set button starts on the value row. Its block runs from here to the panel's right
     * margin, and anything that wants to line up with that block measures from the same number rather
     * than from one written down again beside it.
     */
    private static final int VALUE_BUTTONS_X = 138;

    /** Width of the chance and duration boxes on a type's own row. */
    private static final int SMALL_FIELD_WIDTH = 40;
    /** Space between a caption and the box it names. */
    private static final int LABEL_GAP = 4;
    /** Space between one thing on the row and the next, and the tighter one used when it will not fit. */
    private static final int ITEM_GAP = 8;
    private static final int TIGHT_ITEM_GAP = 4;

    private static final Identifier BG_TEXTURE = Identifier.fromNamespaceAndPath("sce", "textures/gui/sce_bg.png");
    static final Identifier SLOT_TEXTURE = Identifier.fromNamespaceAndPath("sce", "textures/gui/sce_slot.png");
    /** The same slot in a cooler grey, for the ones that take a quantity of fluid instead of an item. */
    private static final Identifier FLUID_SLOT_TEXTURE =
            Identifier.fromNamespaceAndPath("sce", "textures/gui/sce_fluid_slot.png");
    /** The empty-slot art the smithing table shows in its template slot. */
    private static final Identifier TEMPLATE_TEXTURE =
            Identifier.fromNamespaceAndPath("sce", "textures/gui/sce_smithing_upgrade.png");
    private static final Identifier ARROW_TEXTURE = Identifier.fromNamespaceAndPath("sce", "textures/gui/sce_arrow.png");

    // Carries the cursor position across a menu re-open so it isn't recentered (see reopen/init).
    private static double pendingCursorX = -1.0;
    private static double pendingCursorY = -1.0;

    private final int mode;
    private final int inputCount;
    private final int outputCount;
    private final boolean create;
    private final boolean mechanical;
    /** Where the slots and the rows around them sit for this recipe type; rebuilt with the widgets. */
    private EditorLayout layout;
    /** Mechanical crafting only: whether Create should also match the pattern mirrored. */
    private boolean mirrored;

    private final IngredientValue[] overlay =
            new IngredientValue[RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE];
    private final IngredientValue[] overlayOut;
    private final int[] overlayOutCount;
    private final float[] outputChance;
    /** Per-result keys this editor does not model, kept so a result with components survives a save. */
    private final java.util.Map<String, com.google.gson.JsonElement>[] outputExtra;

    private int selectedInput = -1;
    private int selectedOutput = -1;
    /** Whether the slot picked last was an output, so the fluid row knows which one to write to. */
    private boolean selectedIsOutput;

    private EditBox fluidBox;
    private EditBox fluidAmountBox;
    private String fluidValue = "";
    private String fluidAmountValue = Integer.toString(IngredientValue.BUCKET);
    private final StatusLine status = new StatusLine();

    /** The recipe's own type when it is not one this editor writes, for the type button's tooltip. */
    private final String keptType;
    /** The order each rule button walks through, forwards on a click and backwards on a right-click. */
    private static final List<String> MATCH_RULES = List.of("auto", "ignore", "require");
    private static final List<String> CARRY_RULES = List.of("auto", "none", "recipe", "ingredients", "both");

    /** The buttons that cycle, kept so a right-click can ask them whether it landed on one. */
    private Button typeRule;
    private Button matchRule;
    private Button carryRule;
    private Button categoryRule;

    /** Cobblemon's campfire pot only: the tab of its recipe book this recipe goes under. */
    private String potCategory = CookingPot.DEFAULT_CATEGORY;
    /** The item tag naming what a player may drop into the pot's three seasoning slots. */
    private String potTag = CookingPot.NO_SEASONING;
    /** Which of the seven properties the dish absorbs from whatever seasoning it was cooked with. */
    private final List<String> potAbsorbs = new ArrayList<>();
    /** The result as the file spelled it, kept while the author leaves the result item alone. */
    private JsonElement potResult;

    /** How the crafted result's data is decided: {@code auto}, or one of the Carry names. */
    private String carry = "auto";
    /** Whether the ingredients' data is part of the match: {@code auto}, {@code ignore} or {@code require}. */
    private String matchData = "auto";
    private String idValue;
    /** What the value row holds: an item or fluid id, or a tag with a leading {@code #}. */
    private String valueText = "";
    private String amountText = Integer.toString(IngredientValue.BUCKET);
    private float pendingExp = 0.1f;
    private int pendingTime;
    private int heatIndex;
    /** Create's deployer flag, for the two types that have one. */
    private boolean keepHeldItem;

    private EditBox idBox;
    private EditBox valueBox;
    private EditBox amountBox;
    private EditBox expBox;
    private EditBox timeBox;
    private EditBox chanceBox;
    /** Marks fields holding something unusable and completes the ones naming a registry entry. */
    private final FieldAssist fields = new FieldAssist();

    /** The captions on a type's own row, each already at the x init() measured for it. */
    private final List<Caption> extraCaptions = new ArrayList<>();

    /** A caption and where it goes. Worked out with the widget it names, so the two cannot drift apart. */
    private record Caption(Component text, int x) {
    }

    public RecipeEditorScreen(RecipeEditorMenu menu, Inventory inventory, Component title) {
        // The panel's size is settled at construction from 26.1.2: imageWidth and imageHeight are final
        // fields now, so the constructor that takes them is the only way to say how big this screen is.
        super(menu, inventory, title, EditorLayout.WIDTH, EditorLayout.HEIGHT);
        this.mode = menu.mode();
        this.inputCount = menu.inputCount();
        this.outputCount = menu.outputCount();
        this.create = RecipeModes.isCreate(mode);
        this.mechanical = RecipeModes.isMechanicalCrafting(mode);
        this.layout = new EditorLayout(mode);
        this.overlayOut = new IngredientValue[outputCount];
        this.overlayOutCount = new int[outputCount];
        this.outputChance = new float[outputCount];
        @SuppressWarnings("unchecked")
        java.util.Map<String, com.google.gson.JsonElement>[] extras = new java.util.Map[outputCount];
        this.outputExtra = extras;
        this.idValue = menu.editId() != null ? menu.editId().toString() : "sce:new_recipe";
        this.keptType = keptTypeOf(menu.baseDraft(), mode);
        // Once per opening, in case the tags were reloaded while the last screen was closed.
        TagCycle.forget();
        initFromBase(menu.baseDraft());
    }

    /**
     * The type worth telling the author about: one belonging to another mod. A vanilla type, or a
     * Create type this editor writes itself, is what would be written anyway and says nothing.
     */
    private static String keptTypeOf(RecipeDraft base, int mode) {
        if (base == null || base.sourceType.isEmpty() || base.sourceType.startsWith("minecraft:")) {
            return null;
        }
        return base.sourceType.equals(RecipeModes.createType(mode)) ? null : base.sourceType;
    }

    private void initFromBase(RecipeDraft base) {
        carry = base != null ? base.carry : "auto";
        matchData = base != null ? base.matchData : "auto";
        potCategory = CookingPot.category(base != null ? base.potCategory : null);
        potTag = CookingPot.seasoningTag(base != null ? base.potSeasoningTag : null);
        potAbsorbs.clear();
        potResult = null;
        if (base != null) {
            potAbsorbs.addAll(base.potProcessors);
            potResult = base.potResult;
        }
        for (int i = 0; i < overlay.length; i++) {
            overlay[i] = IngredientValue.empty();
        }
        for (int i = 0; i < outputCount; i++) {
            overlayOut[i] = IngredientValue.empty();
            overlayOutCount[i] = 1;
            outputChance[i] = 1.0f;
            outputExtra[i] = new java.util.LinkedHashMap<>();
        }
        // A new cooking recipe starts at the time vanilla uses for its type. Leaving it at zero produces
        // a recipe that crafts but divides by zero in the progress arrow of a recipe viewer.
        if (RecipeModes.isCooking(mode)) {
            pendingTime = RecipeModes.cooking(mode).defaultTime;
        }
        if (base == null) {
            return;
        }
        pendingExp = base.experience;
        pendingTime = base.kind == RecipeDraft.Kind.CREATE_PROCESSING ? base.processingTime : base.cookingTime;
        // A recipe stored before this was validated can still hold a zero. It is repaired when injected,
        // so show the time it actually has rather than one the editor would now refuse to save.
        if (RecipeModes.isCooking(mode) && pendingTime <= 0) {
            pendingTime = RecipeModes.cooking(mode).defaultTime;
        }

        if (base.kind == RecipeDraft.Kind.CRAFTING_SHAPED || base.kind == RecipeDraft.Kind.MECHANICAL_CRAFTING
                || base.kind == RecipeDraft.Kind.COOKING_POT) {
            // Row-major grids: re-index the recipe's own width onto the width this editor shows, clipping
            // anything larger than the grid we can display.
            int columns = gridColumns();
            for (int row = 0; row < base.height && row < columns; row++) {
                for (int col = 0; col < base.width && col < columns; col++) {
                    overlay[row * columns + col] = base.input(row * base.width + col);
                }
            }
            mirrored = base.acceptMirrored;
        } else if (base.kind == RecipeDraft.Kind.CREATE_PROCESSING) {
            // Create writes a recipe's ingredients in file order and tells an item from a fluid by the
            // shape of the entry, not by where it sits. The editor keeps its tanks after its item slots,
            // so they are dealt out by kind: otherwise mixing's water landed in an item slot, where it
            // could not be edited, and the tanks sat empty beside it.
            int itemSlot = 0;
            int fluidSlot = RecipeModes.itemInputs(mode);
            for (IngredientValue value : base.inputs) {
                if (value == null || value.isEmpty()) {
                    continue;
                }
                int target = value.isFluid() ? fluidSlot++ : itemSlot++;
                if (target >= 0 && target < inputCount && target < overlay.length) {
                    overlay[target] = value;
                }
            }
        } else {
            for (int i = 0; i < base.inputs.size() && i < overlay.length; i++) {
                overlay[i] = base.input(i);
            }
        }

        if (base.kind == RecipeDraft.Kind.CREATE_PROCESSING) {
            int itemResult = 0;
            int fluidResult = RecipeModes.itemOutputs(mode);
            for (RecipeDraft.ResultEntry entry : base.results) {
                if (entry.item == null || entry.item.isEmpty()) {
                    continue;
                }
                int target = entry.item.isFluid() ? fluidResult++ : itemResult++;
                if (target < 0 || target >= outputCount) {
                    continue;
                }
                overlayOut[target] = entry.item;
                overlayOutCount[target] = entry.count;
                outputChance[target] = entry.chance;
                outputExtra[target].putAll(entry.extra);
            }
            heatIndex = heatIndexOf(base.heat);
            keepHeldItem = base.keepHeldItem;
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

    // ------------------------------------------------------------------ the campfire pot's seasoning

    /**
     * The group of seasoning items on show right now, narrowed the way Cobblemon's own recipe viewer
     * narrows it: to the ones that are registered seasonings and that this recipe would actually take
     * something from. With nothing absorbed there is no such item, so the whole tag is shown instead.
     */
    private List<ItemStack> seasoningPreview() {
        Identifier tag = Identifier.tryParse(CookingPot.seasoningTag(potTag));
        return TagCycle.window(tag, layout.seasoningCount,
                stack -> CobblemonSeasonings.contributes(stack, potAbsorbs));
    }

    private void openSeasoning() {
        minecraft.setScreen(new SeasoningScreen(this, potTag, potAbsorbs));
    }

    /** Taken back from the seasoning screen on its way out. Written when the recipe is saved. */
    public void applySeasoning(String tag, List<String> absorbs) {
        potTag = CookingPot.seasoningTag(tag);
        potAbsorbs.clear();
        potAbsorbs.addAll(absorbs);
    }

    /** Columns in the input grid; mechanical crafting uses a bigger square than the vanilla 3x3. */
    private int gridColumns() {
        return mechanical ? RecipeDraft.MECHANICAL_SIZE : 3;
    }

    /** Whether this type can require heat. Mixing and compacting can; nothing else does. */
    private boolean heated() {
        return RecipeModes.allowsHeat(mode);
    }

    /** Whether the slot picked last is one that takes a fluid rather than an item. */
    private boolean selectedIsFluid() {
        return selectedIsOutput
                ? selectedOutput >= 0 && RecipeModes.isFluidOutput(mode, selectedOutput)
                : selectedInput >= 0 && RecipeModes.isFluidInput(mode, selectedInput);
    }

    @Override
    protected void init() {
        super.init();
        layout = new EditorLayout(mode);
        fields.clear();
        // The completion list belongs inside the panel, not hanging off its edge.
        fields.limits(leftPos + 2, leftPos + EditorLayout.WIDTH - 2);
        extraCaptions.clear();
        // Rebuilt from scratch every time; the rule buttons are not there for every type, so the
        // references have to go away with them or a right-click would reach a button that is gone.
        matchRule = null;
        carryRule = null;
        categoryRule = null;

        Button.Builder typeButton = Button.builder(
                        Component.translatable("sce.button.type", Component.translatable(RecipeModes.labelKey(mode))),
                        b -> reopen(RecipeModes.nextAvailable(mode)))
                .bounds(leftPos + 45, topPos + 4, 150, 16);
        // A recipe belonging to another mod keeps its own type when saved, which is the difference
        // between editing a backpack recipe and quietly turning it into an ordinary one. Said here
        // rather than on the button face, which has no room for a mod's id.
        typeButton.tooltip(net.minecraft.client.gui.components.Tooltip.create(keptType != null
                ? Component.translatable("sce.tooltip.keeps_type", keptType)
                : Component.translatable("sce.tooltip.type")));
        typeRule = addRenderableWidget(typeButton.build());

        idBox = new EditBox(font, leftPos + 8, topPos + EditorLayout.ID_ROW_Y, 180, 16, Component.translatable("sce.hint.id"));
        idBox.setMaxLength(200);
        idBox.setValue(idValue);
        idBox.setResponder(s -> idValue = s);
        addRenderableWidget(idBox);
        fields.add(idBox, FieldAssist.id(), FieldAssist.Source.RECIPES);
        addRenderableWidget(Button.builder(Component.translatable("sce.button.load"), b -> reopen(-1))
                .bounds(leftPos + 192, topPos + EditorLayout.ID_ROW_Y, 40, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.load"))).build());

        // Only for the crafting types, and only when the recipe is not already another mod's: a mod's own
        // recipe keeps its type and decides these things for itself, so offering them too would be two
        // answers to one question.
        if (RecipeModes.isCrafting(mode) && keptType == null && layout.ruleRowY >= 0) {
            matchRule = addRenderableWidget(Button.builder(
                            ruleLabel(matchData, effectiveRequire() ? "require" : "ignore"),
                            b -> cycleMatchData(1))
                    .bounds(leftPos + 8, topPos + layout.ruleRowY, 110, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("sce.tooltip.match_data")))
                    .build());
            carryRule = addRenderableWidget(Button.builder(
                            ruleLabel(carry, effectiveCarry()),
                            b -> cycleCarry(1))
                    .bounds(leftPos + 122, topPos + layout.ruleRowY, 110, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("sce.tooltip.carry")))
                    .build());
        }

        // Cobblemon's pot needs a book category on every recipe, and a new one has nothing to inherit it
        // from, so it is asked for rather than guessed.
        if (RecipeModes.isCookingPot(mode) && layout.ruleRowY >= 0) {
            categoryRule = addRenderableWidget(Button.builder(
                            Component.translatable("sce.category." + potCategory),
                            b -> cyclePotCategory(1))
                    .bounds(leftPos + 8, topPos + layout.ruleRowY, 110, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("sce.tooltip.pot_category")))
                    .build());
        }
        if (layout.seasoningCount > 0) {
            // Under the slots it belongs to: the seasoning rules are about those three, and a button
            // sitting anywhere else would be about the recipe in general.
            addRenderableWidget(Button.builder(Component.translatable("sce.button.edit"), b -> openSeasoning())
                    .bounds(leftPos + layout.seasoningX - 1, topPos + layout.seasoningButtonY(),
                            layout.seasoningCount * EditorLayout.SLOT, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("sce.tooltip.pot_seasoning")))
                    .build());
        }

        // One row to put something in the slot that was picked last, whatever kind of something that slot
        // takes. It used to be two rows that looked alike - one for a tag, one for a fluid - and between
        // them they took the sixteen pixels that mixing's nine-slot grid needs. What gets written is
        // decided by the slot rather than guessed from the text: a fluid slot reads the id against the
        // fluid registry, an item slot against the item registry, and a leading '#' means a tag of
        // whichever of the two it is.
        int valueRowY = layout.tagRowY;
        boolean fluids = RecipeModes.usesFluids(mode);
        int valueWidth = fluids ? 92 : 126;
        valueBox = new EditBox(font, leftPos + 8, topPos + valueRowY, valueWidth, 16,
                Component.translatable("sce.hint.value"));
        valueBox.setMaxLength(200);
        valueBox.setValue(valueText);
        valueBox.setHint(FieldAssist.hint("sce.hint.value_id"));
        valueBox.setResponder(s -> valueText = s);
        addRenderableWidget(valueBox);
        fields.add(valueBox, FieldAssist.idOrTag(),
                () -> selectedIsFluid() ? FieldAssist.Source.FLUIDS : FieldAssist.Source.ITEMS_OR_TAGS);
        if (fluids) {
            amountBox = new EditBox(font, leftPos + 104, topPos + valueRowY, 30, 16,
                    Component.translatable("sce.hint.amount"));
            amountBox.setValue(amountText);
            amountBox.setResponder(s -> amountText = s);
            // An amount is a fluid's whole quantity; an item ingredient has none and a result counts in
            // its own field, so the box only accepts typing while a fluid slot is the one picked.
            amountBox.setEditable(selectedIsFluid());
            addRenderableWidget(amountBox);
            fields.add(amountBox, FieldAssist.intAtLeast(1));
        }
        addRenderableWidget(Button.builder(Component.translatable("sce.button.set_value"), b -> applyValue())
                .bounds(leftPos + VALUE_BUTTONS_X, topPos + valueRowY, 44, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(fluids ? "sce.tooltip.set_value_fluid" : "sce.tooltip.set_value"))).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.clear_slot"), b -> clearSelected())
                .bounds(leftPos + 186, topPos + valueRowY, 46, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.clear_slot"))).build());

        if (mechanical) {
            // Sits in the free space beside the grid, under the result slot.
            addRenderableWidget(Button.builder(Component.translatable("sce.button.mirrored",
                    Component.translatable(mirrored ? "sce.toggle.on" : "sce.toggle.off")), b -> {
                mirrored = !mirrored;
                rebuildWidgets();
                // Exactly the block Set and Clear occupy under it: same left edge, same right edge.
                // Eighty-four pixels was near enough to look deliberate and far enough to look wrong.
            }).bounds(leftPos + VALUE_BUTTONS_X, topPos + layout.mirroredY,
                    EditorLayout.WIDTH - EditorLayout.PADDING - VALUE_BUTTONS_X, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.mirrored"))).build());
        }

        if (RecipeModes.hasSideColumn(mode)) {
            expBox = new EditBox(font, leftPos + layout.sideX, topPos + layout.expY,
                    EditorLayout.SIDE_FIELD_WIDTH, 16, Component.translatable("sce.hint.exp"));
            expBox.setValue(Float.toString(pendingExp));
            expBox.setResponder(s -> pendingExp = parseFloat(s, pendingExp));
            expBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("sce.tooltip.exp")));
            addRenderableWidget(expBox);
            fields.add(expBox, FieldAssist.decimalBetween(0.0f, Float.MAX_VALUE));
            timeBox = new EditBox(font, leftPos + layout.sideX, topPos + layout.sideTimeY,
                    EditorLayout.SIDE_FIELD_WIDTH, 16, Component.translatable("sce.hint.time"));
            timeBox.setValue(Integer.toString(pendingTime));
            timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
            timeBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("sce.tooltip.cook_time")));
            addRenderableWidget(timeBox);
            // Nothing can display a cooking recipe with no time, and saving refuses one, so the field
            // says so while it is still being typed.
            fields.add(timeBox, FieldAssist.intAtLeast(1));
        }

        // The row below the recipe holds whatever fields the type has, measured and centred rather
        // than placed at fixed pixels.
        if (layout.extraRowY >= 0) {
            buildExtraRow();
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.save"), b -> save()).bounds(leftPos + 8, topPos + EditorLayout.BUTTON_ROW_Y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.disable"), b -> disable()).bounds(leftPos + 64, topPos + EditorLayout.BUTTON_ROW_Y, 58, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.disable"))).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.raw"), b -> openRaw()).bounds(leftPos + 126, topPos + EditorLayout.BUTTON_ROW_Y, 40, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.raw"))).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.close"), b -> onClose()).bounds(leftPos + 170, topPos + EditorLayout.BUTTON_ROW_Y, 62, 20).build());

        // Opening a new menu recenters the cursor (the client briefly returns to the world in between);
        // put it back where it was so cycling the type/loading doesn't yank the mouse to the middle.
        if (pendingCursorX >= 0.0) {
            double cursorX = pendingCursorX;
            double cursorY = pendingCursorY;
            pendingCursorX = -1.0;
            pendingCursorY = -1.0;
            GLFW.glfwSetCursorPos(minecraft.getWindow().handle(), cursorX, cursorY);
        }
    }

    /**
     * One thing on a type's own row: a box with a caption, or a button that carries its own text.
     *
     * <p>Built before anything is placed, because where each one goes depends on how wide all of them
     * are together, and that cannot be known until the last is described.
     */
    private final class RowItem {
        private final Component label;
        private final int boxWidth;
        private final java.util.function.IntConsumer build;

        RowItem(Component label, int boxWidth, java.util.function.IntConsumer build) {
            this.label = label;
            this.boxWidth = boxWidth;
            this.build = build;
        }

        int captionWidth() {
            return label == null ? 0 : font.width(label) + LABEL_GAP;
        }

        int width() {
            return captionWidth() + boxWidth;
        }
    }

    /**
     * The row of fields under the recipe.
     *
     * <p>Measured rather than placed at fixed pixels: the captions are translated, and a column of boxes
     * written for the English word would sit on top of the Spanish one. The group is then centred on the
     * panel, so a type with one field does not leave the right half of its row empty.
     */
    private void buildExtraRow() {
        List<RowItem> items = new ArrayList<>();
        if (RecipeModes.hasChance(mode)) {
            items.add(new RowItem(Component.translatable("sce.label.chance"), SMALL_FIELD_WIDTH, x -> {
                chanceBox = new EditBox(font, leftPos + x, topPos + layout.extraRowY, SMALL_FIELD_WIDTH, 16,
                        Component.translatable("sce.hint.chance"));
                chanceBox.setValue(selectedOutput >= 0 ? Float.toString(outputChance[selectedOutput]) : "1.0");
                chanceBox.setResponder(s -> {
                    if (selectedOutput >= 0) {
                        outputChance[selectedOutput] =
                                Mth.clamp(parseFloat(s, outputChance[selectedOutput]), 0.0f, 1.0f);
                    }
                });
                chanceBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("sce.tooltip.chance")));
                addRenderableWidget(chanceBox);
                fields.add(chanceBox, FieldAssist.decimalBetween(0.0f, 1.0f));
            }));
        }
        if (RecipeModes.allowsDuration(mode)) {
            items.add(new RowItem(Component.translatable("sce.label.time"), SMALL_FIELD_WIDTH, x -> {
                timeBox = new EditBox(font, leftPos + x, topPos + layout.extraRowY, SMALL_FIELD_WIDTH, 16,
                        Component.translatable("sce.hint.time"));
                timeBox.setValue(Integer.toString(pendingTime));
                timeBox.setResponder(s -> pendingTime = parseInt(s, pendingTime));
                timeBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("sce.tooltip.create_time")));
                addRenderableWidget(timeBox);
                // Create fills in its own duration when the recipe leaves this at zero.
                fields.add(timeBox, FieldAssist.intAtLeast(0));
            }));
        }
        if (heated()) {
            Component text = Component.translatable("sce.button.heat",
                    Component.translatable("sce.heat." + HEAT_NAMES[heatIndex]));
            items.add(new RowItem(null, buttonWidth(text), x ->
                    addRenderableWidget(Button.builder(text, b -> {
                        heatIndex = (heatIndex + 1) % HEAT_NAMES.length;
                        rebuildWidgets();
                    }).bounds(leftPos + x, topPos + layout.extraRowY, buttonWidth(text), 16)
                            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                    Component.translatable("sce.tooltip.heat"))).build())));
        }
        if (RecipeModes.hasKeepHeldItem(mode)) {
            // The flag behind every waxing and de-oxidising recipe in the game.
            Component text = Component.translatable("sce.button.keep_held",
                    Component.translatable(keepHeldItem ? "sce.toggle.on" : "sce.toggle.off"));
            items.add(new RowItem(null, buttonWidth(text), x ->
                    addRenderableWidget(Button.builder(text, b -> {
                        keepHeldItem = !keepHeldItem;
                        rebuildWidgets();
                    }).bounds(leftPos + x, topPos + layout.extraRowY, buttonWidth(text), 16)
                            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                                    Component.translatable("sce.tooltip.keep_held"))).build())));
        }
        if (items.isEmpty()) {
            return;
        }

        // One field on its own: its name goes centred above it rather than beside it, which is what the
        // layout left the extra line for.
        if (layout.extraCaptionY >= 0) {
            RowItem only = items.get(0);
            extraCaptions.add(new Caption(only.label, (EditorLayout.WIDTH - font.width(only.label)) / 2));
            only.build.accept((EditorLayout.WIDTH - only.boxWidth) / 2);
            return;
        }

        int available = EditorLayout.WIDTH - 2 * EditorLayout.PADDING;
        int gap = ITEM_GAP;
        int total = totalWidth(items, gap);
        if (total > available) {
            // Tighten the spacing first, and only fall back to the left margin when even that is not
            // enough for what the row holds.
            gap = TIGHT_ITEM_GAP;
            total = totalWidth(items, gap);
        }
        int x = total > available ? EditorLayout.PADDING : (EditorLayout.WIDTH - total) / 2;
        for (RowItem item : items) {
            if (item.label != null) {
                extraCaptions.add(new Caption(item.label, x));
            }
            item.build.accept(x + item.captionWidth());
            x += item.width() + gap;
        }
    }

    private static int totalWidth(List<RowItem> items, int gap) {
        int total = gap * (items.size() - 1);
        for (RowItem item : items) {
            total += item.width();
        }
        return total;
    }

    /** A button wide enough for its own text, so "Super" and "Superheated" both fit. */
    private int buttonWidth(Component text) {
        return Math.max(40, font.width(text) + 12);
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
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        // While the completion list is open it owns the arrows, tab, enter and escape, so escape
        // dismisses the list before it closes the screen.
        if (fields.keyPressed(key)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (getFocused() instanceof EditBox editBox) {
            editBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Steps one cycling button back if the click landed on it. */
    private boolean steppedBack(double mouseX, double mouseY, Button widget, Runnable back) {
        if (widget == null || !widget.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        // Buttons click when pressed; a right-click handled by hand has to say so itself.
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        back.run();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 0 && fields.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        // Every button that cycles steps forward on a normal click and back on a right-click, so
        // overshooting the value you wanted does not mean going all the way round again.
        if (button == 1
                && (steppedBack(mouseX, mouseY, typeRule, () -> reopen(RecipeModes.previousAvailable(mode)))
                || steppedBack(mouseX, mouseY, matchRule, () -> cycleMatchData(-1))
                || steppedBack(mouseX, mouseY, carryRule, () -> cycleCarry(-1))
                || steppedBack(mouseX, mouseY, categoryRule, () -> cyclePotCategory(-1)))) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type) {
        if (slotId >= 0 && slotId < inputCount) {
            selectedInput = slotId;
            selectedIsOutput = false;
            syncValueRow(overlay[slotId]);
        } else if (slotId >= inputCount && slotId < inputCount + outputCount) {
            selectedOutput = slotId - inputCount;
            selectedIsOutput = true;
            if (chanceBox != null) {
                chanceBox.setValue(Float.toString(outputChance[selectedOutput]));
            }
            syncValueRow(overlayOut[selectedOutput]);
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    /**
     * Mirrors the picked slot into the value row, so what is in it can be read and adjusted rather than
     * retyped. An ingredient this editor only carries through has no text form to show, so the row is
     * left empty for it: whatever is typed there replaces it outright, which is the only thing that
     * could be meant.
     */
    private void syncValueRow(IngredientValue current) {
        if (valueBox == null) {
            return;
        }
        if (current != null && !current.isEmpty() && !current.isRaw() && current.id() != null) {
            boolean tagged = current.isFluidTag() || current.kind() == IngredientValue.Kind.TAG;
            valueText = (tagged ? "#" : "") + current.id();
            if (current.isFluid()) {
                amountText = Integer.toString(current.amount());
            }
        } else {
            valueText = "";
        }
        valueBox.setValue(valueText);
        syncAmountBox();
    }

    /**
     * Shows the amount only where there is an amount to show. A number sitting beside an item slot,
     * greyed out and unlabelled, reads as a field nobody can explain; an empty box with "mB" in it says
     * what it is for and that this slot is not it.
     */
    private void syncAmountBox() {
        if (amountBox == null) {
            return;
        }
        boolean fluidSlot = selectedIsFluid();
        amountBox.setValue(fluidSlot ? amountText : "");
        amountBox.setEditable(fluidSlot);
    }

    /**
     * Puts what the value row names into whichever recipe slot was picked last.
     *
     * <p>What gets written is decided by the slot, not guessed from the text. A fluid slot reads the id
     * against the fluid registry and takes the amount beside it; every other slot reads it against the
     * item registry. A leading {@code #} means a tag of whichever of the two the slot takes. An id that
     * names nothing is refused here rather than written and rejected by the server later - which is what
     * used to turn {@code minecraft:water} in a Filling step into an item ingredient and an invalid
     * recipe.
     */
    private void applyValue() {
        String typed = valueText.trim();
        if (typed.isEmpty()) {
            status.set(Component.translatable("sce.status.click_slot_first"));
            return;
        }
        boolean tagged = typed.startsWith("#");
        Identifier id = Identifier.tryParse(tagged ? typed.substring(1) : typed);
        boolean output = selectedIsOutput && selectedOutput >= 0;
        if (!output && selectedInput < 0) {
            status.set(Component.translatable("sce.status.click_slot_first"));
            return;
        }
        boolean fluidSlot = selectedIsFluid();
        if (id == null) {
            status.set(Component.translatable(fluidSlot ? "sce.status.invalid_fluid" : "sce.status.invalid_item"));
            return;
        }
        // A result names one concrete thing; a tag is a set of them and there would be no way to say
        // which one came out.
        if (tagged && output) {
            status.set(Component.translatable(fluidSlot ? "sce.status.fluid_tag_output" : "sce.status.tag_output"));
            return;
        }
        if (!tagged && !(fluidSlot ? BuiltInRegistries.FLUID.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id))) {
            status.set(Component.translatable(fluidSlot ? "sce.status.invalid_fluid" : "sce.status.invalid_item"));
            return;
        }
        IngredientValue value;
        if (fluidSlot) {
            int amount = Math.max(1, parseInt(amountText, IngredientValue.BUCKET));
            value = tagged ? IngredientValue.fluidTag(id, amount) : IngredientValue.fluid(id, amount);
        } else {
            value = tagged ? IngredientValue.tag(id) : IngredientValue.item(id);
        }
        if (output) {
            if (!menu.outputItem(selectedOutput).isEmpty()) {
                status.set(Component.translatable("sce.status.clear_slot_first"));
                return;
            }
            overlayOut[selectedOutput] = value;
            return;
        }
        if (!menu.gridItem(selectedInput).isEmpty()) {
            status.set(Component.translatable("sce.status.clear_slot_first"));
            return;
        }
        overlay[selectedInput] = value;
    }

    private void clearSelected() {
        if (selectedIsOutput && selectedOutput >= 0) {
            overlayOut[selectedOutput] = IngredientValue.empty();
            outputExtra[selectedOutput].clear();
        } else if (selectedInput >= 0) {
            overlay[selectedInput] = IngredientValue.empty();
        }
        syncValueRow(IngredientValue.empty());
    }


    // ------------------------------------------------------------------ data rules

    /**
     * What {@code auto} means for the ingredients right now: require their data if the author actually
     * put data-carrying items in the grid, because going to the trouble of placing a named chest is
     * what asking for that chest looks like.
     */
    private boolean autoRequires() {
        for (int i = 0; i < inputCount; i++) {
            ItemStack stack = menu.gridItem(i);
            if (!stack.isEmpty() && !stack.getComponentsPatch().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** What {@code auto} means for the result: whatever data is actually on the table, from either side. */
    private String autoCarry() {
        boolean fromResult = resultHasData();
        boolean fromGrid = autoRequires();
        if (fromResult && fromGrid) {
            return "both";
        }
        if (fromResult) {
            return "recipe";
        }
        return fromGrid ? "ingredients" : "none";
    }

    private boolean resultHasData() {
        ItemStack stack = menu.outputSlot(0).getItem();
        return !stack.isEmpty() && !stack.getComponentsPatch().isEmpty();
    }

    private boolean effectiveRequire() {
        return matchData.equals("auto") ? autoRequires() : matchData.equals("require");
    }

    private String effectiveCarry() {
        return carry.equals("auto") ? autoCarry() : carry;
    }

    /**
     * The button face: what the rule amounts to right now, marked when the editor is the one deciding.
     *
     * <p>Only the answer, because the question is written in the caption above the button — two of these
     * share a row, and a face that repeated the question had no room left to give the answer.
     */
    private Component ruleLabel(String setting, String effective) {
        Component value = Component.translatable("sce.rule." + effective);
        return setting.equals("auto") ? Component.translatable("sce.rule.auto", value) : value;
    }

    private void cycleMatchData(int by) {
        matchData = stepRule(MATCH_RULES, matchData, by);
        rebuildWidgets();
    }

    private void cycleCarry(int by) {
        carry = stepRule(CARRY_RULES, carry, by);
        rebuildWidgets();
    }

    private void cyclePotCategory(int by) {
        potCategory = stepRule(CookingPot.CATEGORIES, potCategory, by);
        rebuildWidgets();
    }

    /** One step along a rule's order, wrapping at both ends so a right-click is a real way back. */
    private static String stepRule(List<String> rules, String current, int by) {
        int index = Math.max(0, rules.indexOf(current));
        return rules.get(Math.floorMod(index + by, rules.size()));
    }

    private RecipeDraft buildDraft(Identifier id) {
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
            // A duration or a heat a type cannot use is not merely ignored by Create: the recipe fails
            // to load. So they are written only where the type has a field for them.
            draft.processingTime = RecipeModes.allowsDuration(mode) ? pendingTime : 0;
            draft.heat = heated() ? HEAT_NAMES[heatIndex] : "none";
            draft.keepHeldItem = RecipeModes.hasKeepHeldItem(mode) && keepHeldItem;
            draft.results.clear();
            for (int i = 0; i < outputCount; i++) {
                IngredientValue item = resolveOutput(i);
                if (!item.isEmpty()) {
                    RecipeDraft.ResultEntry entry =
                            new RecipeDraft.ResultEntry(item, resolveOutputCount(i), outputChance[i]);
                    entry.extra.putAll(outputExtra[i]);
                    draft.results.add(entry);
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
        // Whatever the recipe carried that this editor does not model goes back out with it, and
        // so does its own type. Both are absent when the type button was used, because that
        // re-opens the editor without the stored recipe — which is how you say "make it plain".
        // Not when the recipe's own type is being kept: the buttons are not on screen then, so anything
        // captured here would be a rule nobody asked for — and writing one would change the type back to
        // ours and undo the whole point of keeping theirs.
        if (RecipeModes.isCrafting(mode) && keptType == null) {
            // Captured from what is physically in the slots, which is the whole point: the author shows
            // the editor the chest they mean rather than describing it.
            draft.carry = effectiveCarry();
            draft.matchData = effectiveRequire() ? "require" : "ignore";
            draft.requiredStacks.clear();
            if (effectiveRequire()) {
                for (int i = 0; i < inputCount; i++) {
                    ItemStack stack = menu.gridItem(i);
                    if (!stack.isEmpty() && !stack.getComponentsPatch().isEmpty()) {
                        draft.requiredStacks.add(InheritingCraftingRecipe.writeStack(minecraft.level.registryAccess(), stack));
                    }
                }
            }
            ItemStack out = menu.outputSlot(0).getItem();
            draft.resultStack = !out.isEmpty() && !out.getComponentsPatch().isEmpty()
                    ? InheritingCraftingRecipe.writeStack(minecraft.level.registryAccess(), out) : "";
        }
        draft.potCategory = potCategory;
        draft.potSeasoningTag = CookingPot.seasoningTag(potTag);
        draft.potProcessors.clear();
        draft.potProcessors.addAll(potAbsorbs);
        draft.potResult = potResult;
        RecipeDraft base = menu.baseDraft();
        if (base != null) {
            draft.sourceType = base.sourceType;
            draft.extras.putAll(base.extras);
        }
        return draft;
    }

    private void save() {
        Identifier id = Identifier.tryParse(idValue);
        if (id == null) {
            status.set(Component.translatable("sce.status.invalid_id"));
            return;
        }
        // Nothing can display a cooking recipe with no time — a viewer divides by it to animate its
        // progress arrow — so say so now rather than saving something other than what is on screen.
        if (RecipeModes.isCooking(mode) && pendingTime <= 0) {
            status.set(Component.translatable("sce.status.time_required"));
            return;
        }
        SceNetworking.sendSave(id, RecipeCompiler.toJson(buildDraft(id)).toString());
        status.set(Component.translatable("sce.status.saving", id.toString()));
    }

    /**
     * Called from the network layer with the server's verdict on a save request.
     *
     * <p>A save that worked goes back to the manager and says so there: that is where the recipe just
     * saved can be seen in the list, so the confirmation and the thing it confirms are on the same
     * screen. A save that failed stays here, because the form that has to be fixed is here.
     */
    public void onSaveResult(Identifier id, boolean ok) {
        if (ok) {
            RecipeManagerScreen.showOnOpen(Component.translatable("sce.status.saved", id.toString()));
            // Through onClose rather than straight to the other screen: this one is a container screen,
            // and walking away without closing it leaves the server holding a menu nobody is looking at.
            onClose();
            minecraft.setScreen(new RecipeManagerScreen());
            return;
        }
        status.set(Component.translatable("sce.status.save_failed", id.toString()));
    }

    /** Opens the raw-JSON view for this recipe: the server's stored JSON if any, else the current draft. */
    private void openRaw() {
        Identifier id = Identifier.tryParse(idValue);
        if (id == null) {
            status.set(Component.translatable("sce.status.invalid_id"));
            return;
        }
        ClientEditorState.requestJson(id, json -> {
            String text = json != null ? json.toString() : RecipeCompiler.toJson(buildDraft(id)).toString();
            minecraft.setScreen(new RawRecipeScreen(id, text));
        });
    }

    private void disable() {
        Identifier id = Identifier.tryParse(idValue);
        if (id == null) {
            status.set(Component.translatable("sce.status.invalid_id"));
            return;
        }
        SceNetworking.sendSimple(SceNetworking.DISABLE, id);
        status.set(Component.translatable("sce.status.requested_disable", id.toString()));
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
        // A fluid slot is not a container: it holds a quantity, so an item dropped there is refused
        // rather than silently ignored once the recipe is saved.
        if (index >= 0 && index < inputCount && !stack.isEmpty() && !RecipeModes.isFluidInput(mode, index)) {
            SceNetworking.sendSetSlot(index, stack.copy());
        }
    }

    public void setGhostOutput(int index, ItemStack stack) {
        if (index >= 0 && index < outputCount && !stack.isEmpty() && !RecipeModes.isFluidOutput(mode, index)) {
            SceNetworking.sendSetSlot(inputCount + index, stack.copy());
        }
    }

    /** Whether this recipe type takes fluids at all; only Create's do. */
    /** Whether this type has anywhere to put a fluid at all, which is what a viewer asks before dragging. */
    public boolean acceptsFluids() {
        return RecipeModes.usesFluids(mode);
    }

    /**
     * Drops a fluid dragged in from a recipe viewer into a slot. A drag carries no amount worth trusting,
     * so it always lands as one bucket; anything else is typed into the fluid row.
     */
    public boolean setGhostInputFluid(int index, Fluid fluid) {
        Identifier id = FluidSprites.idOf(fluid);
        if (id == null || index < 0 || index >= inputCount || !RecipeModes.isFluidInput(mode, index)) {
            return false;
        }
        // The slot's own item would otherwise keep winning over the fluid drawn on top of it.
        if (!menu.gridItem(index).isEmpty()) {
            SceNetworking.sendSetSlot(index, ItemStack.EMPTY);
        }
        overlay[index] = IngredientValue.fluid(id, IngredientValue.BUCKET);
        return true;
    }

    public boolean setGhostOutputFluid(int index, Fluid fluid) {
        Identifier id = FluidSprites.idOf(fluid);
        if (id == null || index < 0 || index >= outputCount || !RecipeModes.isFluidOutput(mode, index)) {
            return false;
        }
        if (!menu.outputItem(index).isEmpty()) {
            SceNetworking.sendSetSlot(inputCount + index, ItemStack.EMPTY);
        }
        overlayOut[index] = IngredientValue.fluid(id, IngredientValue.BUCKET);
        return true;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The container's own background hook is gone from 26.1.2; a screen's single background pass is
        // where the panel goes now. Super first, because that pass is also what dims and blurs the world
        // behind the screen — skipping it would leave the editor floating over a live, sharp world.
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        // The texture coordinates come before the size now, and the whole draw names its pipeline.
        graphics.blit(RenderPipelines.GUI_TEXTURED, BG_TEXTURE, leftPos, topPos, 0.0F, 0.0F,
                imageWidth, imageHeight, imageWidth, imageHeight);

        for (Slot slot : menu.slots) {
            drawSlot(graphics, leftPos + slot.x, topPos + slot.y, false);
        }
        // Over the top of the plain ones: a fluid slot takes no item at all, and saying so with the slot
        // itself is what stops a Filling recipe being filled in as two items and rejected.
        for (int i = 0; i < inputCount; i++) {
            if (RecipeModes.isFluidInput(mode, i)) {
                Slot slot = menu.inputSlot(i);
                drawSlot(graphics, leftPos + slot.x, topPos + slot.y, true);
            }
        }
        for (int i = 0; i < outputCount; i++) {
            if (RecipeModes.isFluidOutput(mode, i)) {
                Slot slot = menu.outputSlot(i);
                drawSlot(graphics, leftPos + slot.x, topPos + slot.y, true);
            }
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
                graphics.text(font, "%", x + 1, y + 1, 0xFFB07000, false);
            }
        }
        if (layout.seasoningCount > 0) {
            // Not slots the author fills: the recipe never names seasoning items. They show what the tag
            // currently admits, in the place the pot itself puts them, so the editor reads as the block.
            List<ItemStack> preview = seasoningPreview();
            for (int i = 0; i < layout.seasoningCount; i++) {
                int x = leftPos + layout.seasoningX + i * EditorLayout.SLOT;
                int y = topPos + layout.seasoningY;
                drawSlot(graphics, x, y, false);
                if (i < preview.size()) {
                    graphics.fakeItem(preview.get(i), x, y);
                    graphics.fill(x, y, x + 16, y + 16, 0x50303030);
                }
            }
        }
        if (RecipeModes.isSmithing(mode) && menu.gridItem(0).isEmpty()
                && (overlay[0] == null || overlay[0].isEmpty())) {
            // What the smithing table draws in its first slot while nothing is in it. Hidden the moment
            // something is — the table hides it too, and leaving it under an item is what made the trim
            // outline poke out from behind a netherite upgrade.
            Slot template = menu.inputSlot(0);
            drawTemplateHint(graphics, leftPos + template.x, topPos + template.y);
        }
        Slot firstOut = menu.outputSlot(0);
        int inputRight = 0;
        for (int i = 0; i < inputCount; i++) {
            inputRight = Math.max(inputRight, menu.inputSlot(i).x + 16);
        }
        // Only draw the arrow when the gap between grid and result can hold it; the mechanical crafting
        // grid reaches close enough to the result slot that it would sit on top of both.
        if (firstOut.x - inputRight >= 22) {
            int arrowX = leftPos + (inputRight + firstOut.x) / 2 - 11;
            int outBottom = menu.outputSlot(outputCount - 1).y + 16;
            int arrowY = topPos + (firstOut.y + outBottom) / 2 - 8;
            graphics.blit(RenderPipelines.GUI_TEXTURED, ARROW_TEXTURE, arrowX, arrowY, 0.0F, 0.0F, 22, 15, 22, 15);
        }
    }

    /** The template slot's own art. Drawn only while the slot is empty, which is the table's rule too. */
    private void drawTemplateHint(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEMPLATE_TEXTURE, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
    }

    private void drawSlot(GuiGraphicsExtractor graphics, int x, int y, boolean fluid) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, fluid ? FLUID_SLOT_TEXTURE : SLOT_TEXTURE,
                x - 1, y - 1, 0.0F, 0.0F, 18, 18, 18, 18);
    }

    private void drawGhost(GuiGraphicsExtractor graphics, IngredientValue value, int x, int y, int count) {
        if (value == null || value.isEmpty()) {
            return;
        }
        // A fluid is drawn as its own texture, the way a recipe viewer shows it; only if it has none does
        // it fall back to the bucket stand-in below.
        boolean drawn = value.isFluid() && FluidSprites.render(graphics, FluidSprites.resolve(value), x, y, 0.7F);
        ItemStack stack = drawn ? ItemStack.EMPTY : stackFor(value);
        if (!stack.isEmpty()) {
            stack.setCount(Math.max(1, count));
            // Ghosts are only a preview of the current definition, so draw them at 70% opacity.
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            graphics.text(font, "#", x + 1, y + 1, 0xFF55FF55, false);
        } else if (value.isFluid()) {
            // A fluid is a quantity rather than an item, so mark the slot and show how much it is.
            graphics.text(font, value.isFluidTag() ? "#" : "~", x + 1, y + 1, 0xFF55AAFF, false);
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y + 10);
            graphics.pose().scale(0.5F, 0.5F);
            graphics.text(font, shortAmount(value.amount()), 0, 0, 0xFF9CDCFF, false);
            graphics.pose().popMatrix();
        }
    }

    /** Compact millibucket label: 1000 mB shows as "1B", anything else as its mB count. */
    private static String shortAmount(int millibuckets) {
        return millibuckets % IngredientValue.BUCKET == 0
                ? (millibuckets / IngredientValue.BUCKET) + "B"
                : millibuckets + "mb";
    }

    private static ItemStack stackFor(IngredientValue value) {
        if (value.isRaw()) {
            // Everything the ingredient admits, walked through the way a tag is, so the slot shows what
            // a recipe viewer shows instead of the first entry and nothing else. A block tag's id is not
            // an item id, which is why reading it as one left the slot empty.
            ItemStack shown = TagCycle.rawItem(value.toIngredientJson());
            return shown.isEmpty() ? new ItemStack(Items.BARRIER) : shown;
        }
        if (value.id() == null) {
            return new ItemStack(Items.BARRIER);
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            // The tag's own members, walked through one at a time. The name tag is only what is left when
            // the tag holds nothing to walk: it says "a tag" and nothing more, which is all there is.
            ItemStack shown = TagCycle.item(value.id());
            return shown.isEmpty() ? new ItemStack(Items.NAME_TAG) : shown;
        }
        if (value.isFluid()) {
            // Show the fluid's own bucket, so water reads as water rather than as an empty bucket. A fluid
            // without one (or a whole tag of them) falls back to the empty bucket.
            Fluid fluid = BuiltInRegistries.FLUID.getValue(value.id());
            if (fluid != null && !value.isFluidTag()) {
                ItemStack bucket = new ItemStack(fluid.getBucket());
                if (!bucket.isEmpty()) {
                    return bucket;
                }
            }
            return new ItemStack(Items.BUCKET);
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(value.id()));
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (RecipeModes.hasSideColumn(mode)) {
            // Right-aligned against their fields, so the pair reads as one column wherever the layout
            // put it rather than needing a position of its own.
            drawRightAligned(graphics, Component.translatable("sce.label.xp"), layout.sideX - 4, layout.expY + 4);
            drawRightAligned(graphics, Component.translatable("sce.label.time"), layout.sideX - 4, layout.sideTimeY + 4);
        }
        // The captions on a type's own row, each where init() measured it: beside its box, or centred
        // on the line above when the row holds that one field and nothing else.
        int captionY = layout.extraCaptionY >= 0 ? layout.extraCaptionY : layout.extraRowY + 4;
        for (Caption caption : extraCaptions) {
            graphics.text(font, caption.text(), caption.x(), captionY, 0xFF404040, false);
        }
        if (RecipeModes.isCrafting(mode) && keptType == null && layout.ruleRowY >= 0) {
            // The captions for the two rule buttons, in the line the layout keeps free above them.
            // Left-aligned on their button and in the same ink as every other label on the panel: white
            // with a shadow read as a heading shouting over the rest of the screen.
            int y = layout.ruleRowY - EditorLayout.LABEL_LINE + 1;
            graphics.text(font, Component.translatable("sce.label.match_data"), 8, y, 0xFF000000, false);
            graphics.text(font, Component.translatable("sce.label.carry"), 122, y, 0xFF000000, false);
        }
        if (RecipeModes.isCookingPot(mode) && layout.ruleRowY >= 0) {
            graphics.text(font, Component.translatable("sce.label.pot_category"), 8,
                    layout.ruleRowY - EditorLayout.LABEL_LINE + 1, 0xFF000000, false);
        }
        if (layout.seasoningCount > 0) {
            // A pixel higher than a caption normally sits, and flush with the frames rather than with
            // the items inside them: otherwise the g of "Seasoning" lands on the top edge of a slot.
            graphics.text(font, Component.translatable("sce.label.pot_seasoning"),
                    layout.seasoningX - 1, layout.seasoningY - EditorLayout.LABEL_LINE, 0xFF000000, false);
        }
        status.drawCentered(graphics, font, imageWidth / 2, imageHeight + 4);
    }

    private void drawRightAligned(GuiGraphicsExtractor graphics, Component text, int right, int y) {
        graphics.text(font, text, right - font.width(text), y, 0xFF404040, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Before the widgets draw: what this decides is read by the fields as they render.
        fields.update(mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        fields.render(graphics, font);
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            ItemStack stack = hoveredSlot.getItem();
            graphics.setTooltipForNextFrame(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
            return;
        }
        if (layout.seasoningCount > 0) {
            // They hold nothing and take no click, but they are showing real items and the author has
            // every reason to want to know which.
            List<ItemStack> preview = seasoningPreview();
            for (int i = 0; i < preview.size(); i++) {
                int x = leftPos + layout.seasoningX + i * EditorLayout.SLOT;
                int y = topPos + layout.seasoningY;
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    ItemStack stack = preview.get(i);
                    graphics.setTooltipForNextFrame(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(),
                            mouseX, mouseY);
                    return;
                }
            }
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

    private void ghostTooltip(GuiGraphicsExtractor graphics, IngredientValue value, int mouseX, int mouseY) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.kind() == IngredientValue.Kind.TAG) {
            // The item on show, then the tag it came out of and how big that tag is — the three things
            // an author needs to judge a tag they did not write.
            List<Component> lines = new ArrayList<>();
            ItemStack shown = TagCycle.item(value.id());
            if (!shown.isEmpty()) {
                lines.addAll(getTooltipFromContainerItem(shown));
            }
            lines.add(Component.literal("#" + value.id()).withStyle(ChatFormatting.GREEN));
            lines.add(Component.translatable("sce.tooltip.tag_members", TagCycle.items(value.id()).size())
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
        } else if (value.isFluid()) {
            graphics.setTooltipForNextFrame(font, Component.literal((value.isFluidTag() ? "#" : "") + value.id()
                    + " (" + value.amount() + " mB)").withStyle(ChatFormatting.AQUA), mouseX, mouseY);
        } else if (value.isRaw()) {
            // The option on show, how many there are, and - the part that matters - that leaving it
            // alone is safe: saving writes the ingredient back exactly as it came.
            List<Component> lines = new ArrayList<>();
            ItemStack shown = stackFor(value);
            if (!shown.isEmpty()) {
                lines.addAll(getTooltipFromContainerItem(shown));
            }
            int options = TagCycle.rawOptions(value.toIngredientJson()).size();
            if (options > 1) {
                lines.add(Component.translatable("sce.tooltip.kept_options", options)
                        .withStyle(ChatFormatting.GREEN));
            }
            lines.add(Component.translatable("sce.tooltip.kept_ingredient").withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("sce.tooltip.kept_ingredient_hint").withStyle(ChatFormatting.DARK_GRAY));
            graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
        } else {
            ItemStack stack = stackFor(value);
            graphics.setTooltipForNextFrame(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
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
