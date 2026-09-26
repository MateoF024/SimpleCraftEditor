package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.edit.SequencedAssemblyCompiler;
import org.mateof24.sce.net.SceNetworking;


/**
 * Editor for Create's sequenced assembly, which is the one recipe that is not a single recipe: a base
 * ingredient is carried through an ordered list of processing steps — each a recipe of its own — looping a
 * number of times before yielding the results. That does not fit the shared slot layout, so it gets a
 * screen of its own: the header holds the base, transitional item and loop count, and below it the steps
 * are listed and can be added, retyped, refilled and removed in place.
 *
 * <p>There is no inventory or recipe viewer here to drag items from, so every id field completes against
 * the live item registry ({@link FieldAssist}).
 */
@Environment(EnvType.CLIENT)
public class SequencedAssemblyScreen extends BaseSceScreen {
    /** Processing types Create accepts as a step; cycled through by each step's type button. */
    private static final String[] STEP_TYPES = {
            "create:deploying", "create:pressing", "create:cutting", "create:filling"};

    private static final int ROW_TYPE = 28;
    private static final int ROW_ID = 60;
    private static final int ROW_PARTS = 92;
    private static final int ROW_RESULT = 124;
    private static final int STEPS_HEADER = 152;
    private static final int STEP_TOP = 176;
    private static final int STEP_HEIGHT = 22;

    private final RecipeDraft draft;
    private final FieldAssist fields = new FieldAssist();

    private String idValue;
    private final StatusLine status = new StatusLine();
    private int scroll;
    /**
     * Which entry of the result pool the result row is editing. A sequence does not yield one thing: it
     * draws from a weighted pool, and the precision mechanism's runs to nine entries. Showing only the
     * first of them was why the other eight could never be touched.
     */
    private int resultIndex;

    public SequencedAssemblyScreen(Identifier id, String json) {
        super(Component.translatable("sce.sequence.title"));
        this.idValue = id.toString();
        this.draft = parse(id, json);
    }

    private static RecipeDraft parse(Identifier id, String json) {
        if (!json.isEmpty()) {
            try {
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                RecipeDraft parsed = SequencedAssemblyCompiler.fromJson(id, object);
                if (parsed != null) {
                    // This screen compiles the recipe itself instead of going through RecipeCompiler,
                    // so it has to keep the parts nobody models the same way the main editor does.
                    RecipeCompiler.preserveFrom(parsed, object);
                }
                return parsed;
            } catch (Exception ignored) {
                // fall through to a blank recipe rather than failing to open
            }
        }
        RecipeDraft blank = RecipeDraft.blank(RecipeDraft.Kind.SEQUENCED_ASSEMBLY);
        blank.id = id;
        return blank;
    }

    /** How many step rows fit between the list and the buttons, leaving the status line clear. */
    private int visibleSteps() {
        return Math.max(1, (height - 56 - STEP_TOP) / STEP_HEIGHT);
    }

    @Override
    protected void init() {
        fields.clear();
        int left = width / 2 - 155;

        addRenderableWidget(Button.builder(Component.translatable("sce.button.type",
                        Component.translatable(RecipeModes.labelKey(sequenceMode()))), b ->
                        SceNetworking.sendOpenEditor(idValue, RecipeModes.nextAvailable(sequenceMode())))
                .bounds(left, ROW_TYPE, 310, 16).build());

        textBox(left, ROW_ID, 250, idValue, s -> idValue = s, "sce.hint.id", FieldAssist.id(),
                FieldAssist.Source.RECIPES);
        addRenderableWidget(Button.builder(Component.translatable("sce.button.load"), b ->
                        SceNetworking.sendOpenEditor(idValue, -1))
                .bounds(left + 256, ROW_ID, 54, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.load"))).build());

        // The base a sequence starts from is an ingredient, so it can be a tag - Create's own precision
        // mechanism starts from #c:plates/gold. Reading it as a plain id marked it red and would have
        // turned it into one item the moment anybody retyped it.
        textBox(left, ROW_PARTS, 130, idOf(draft.input(0)),
                s -> draft.setInput(0, itemOrTagOf(s)), "sce.hint.sequence_base",
                FieldAssist.idOrTag(), FieldAssist.Source.ITEMS_OR_TAGS);
        textBox(left + 136, ROW_PARTS, 130, idOf(draft.transitionalItem),
                s -> draft.transitionalItem = itemOf(s), "sce.hint.sequence_transitional", FieldAssist.id(), FieldAssist.Source.ITEMS);
        textBox(left + 272, ROW_PARTS, 38, Integer.toString(draft.loops),
                s -> draft.loops = Math.max(1, parseInt(s, draft.loops)), "sce.hint.sequence_loops", FieldAssist.intAtLeast(1), FieldAssist.Source.NONE);

        resultIndex = Mth.clamp(resultIndex, 0, Math.max(0, draft.results.size() - 1));
        RecipeDraft.ResultEntry result = currentResult();
        textBox(left, ROW_RESULT, 150, idOf(result.item),
                s -> result.item = itemOf(s), "sce.hint.sequence_result", FieldAssist.id(), FieldAssist.Source.ITEMS);
        textBox(left + 154, ROW_RESULT, 32, Integer.toString(result.count),
                s -> result.count = Math.max(1, parseInt(s, result.count)), "sce.hint.amount", FieldAssist.intAtLeast(1), FieldAssist.Source.NONE);
        // A weight, not a probability: Create picks one entry out of the pool in proportion to it, so
        // values well above one are normal and the field has to take them.
        textBox(left + 190, ROW_RESULT, 40, trimFloat(result.chance),
                s -> result.chance = Math.max(0.0f, parseFloat(s, result.chance)),
                "sce.hint.sequence_weight", FieldAssist.decimalBetween(0.0f, Float.MAX_VALUE), FieldAssist.Source.NONE);
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            resultIndex = Math.max(0, resultIndex - 1);
            rebuildWidgets();
        }).bounds(left + 234, ROW_RESULT, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            resultIndex = Math.min(draft.results.size() - 1, resultIndex + 1);
            rebuildWidgets();
        }).bounds(left + 252, ROW_RESULT, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            draft.results.add(new RecipeDraft.ResultEntry(IngredientValue.empty(), 1, 1.0f));
            resultIndex = draft.results.size() - 1;
            rebuildWidgets();
        }).bounds(left + 270, ROW_RESULT, 18, 16)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("sce.tooltip.add_result"))).build());
        addRenderableWidget(Button.builder(Component.literal("x"), b -> {
            if (draft.results.size() > 1) {
                draft.results.remove(resultIndex);
                resultIndex = Math.max(0, resultIndex - 1);
                rebuildWidgets();
            }
        }).bounds(left + 292, ROW_RESULT, 18, 16)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("sce.tooltip.remove_result"))).build());

        addRenderableWidget(Button.builder(Component.translatable("sce.button.add_step"), b -> {
            draft.sequence.add(blankStep());
            scroll = Math.max(0, draft.sequence.size() - visibleSteps());
            rebuildWidgets();
        }).bounds(left + 210, STEPS_HEADER - 4, 100, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.add_step"))).build());

        int visible = visibleSteps();
        scroll = Mth.clamp(scroll, 0, Math.max(0, draft.sequence.size() - visible));
        for (int row = 0; row < visible && scroll + row < draft.sequence.size(); row++) {
            int index = scroll + row;
            RecipeDraft step = draft.sequence.get(index);
            int y = STEP_TOP + row * STEP_HEIGHT;
            addRenderableWidget(Button.builder(Component.literal(shortType(step.createType)), b -> {
                step.createType = nextType(step.createType, 1);
                rebuildWidgets();
            }).bounds(left + 20, y, 92, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("sce.tooltip.step_type"))).build());
            // Filling is the spout: what it applies is a quantity of fluid, not an item. Typing an item
            // id here used to be written as a second item ingredient, which is one more than a Filling
            // recipe is allowed, so the recipe was refused with no hint as to why.
            boolean fluidStep = isFillingStep(step);
            int idWidth = fluidStep ? 116 : 150;
            // The two boxes of a filling step share the amount, so it survives being typed in either
            // order: setting the amount before there is a fluid to put it on used to lose it.
            int[] amount = {stepAmount(step)};
            textBox(left + 116, y + 2, idWidth, idOf(step.input(0)),
                    s -> step.setInput(0, valueFor(step, s, amount[0])), "sce.hint.sequence_step_item",
                    FieldAssist.idOrTag(),
                    fluidStep ? FieldAssist.Source.FLUIDS : FieldAssist.Source.ITEMS_OR_TAGS);
            if (fluidStep) {
                textBox(left + 236, y + 2, 34, Integer.toString(amount[0]), s -> {
                    amount[0] = Math.max(1, parseInt(s, amount[0]));
                    step.setInput(0, withAmount(step.input(0), amount[0]));
                }, "sce.hint.amount", FieldAssist.intAtLeast(1), FieldAssist.Source.NONE);
            }
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                draft.sequence.remove(index);
                rebuildWidgets();
            }).bounds(left + 272, y, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.save"), b -> save())
                .bounds(left, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("sce.button.disable"), b -> disable())
                .bounds(left + 105, height - 26, 100, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sce.tooltip.disable"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + 210, height - 26, 100, 20).build());
    }

    /** The editor mode this screen stands in for, so its type button can walk on to the others. */
    private static int sequenceMode() {
        for (int i = 0; i < RecipeModes.COUNT; i++) {
            if (RecipeModes.isSequencedAssembly(i)) {
                return i;
            }
        }
        return 0;
    }

    private EditBox textBox(int x, int y, int w, String value, java.util.function.Consumer<String> onChange,
                            String hintKey, java.util.function.Predicate<String> rule, FieldAssist.Source source) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.translatable(hintKey));
        box.setMaxLength(200);
        box.setValue(value);
        box.setResponder(onChange);
        addRenderableWidget(box);
        fields.add(box, rule, source);
        return box;
    }

    private RecipeDraft.ResultEntry currentResult() {
        if (draft.results.isEmpty()) {
            draft.results.add(new RecipeDraft.ResultEntry(IngredientValue.empty(), 1, 1.0f));
        }
        return draft.results.get(Mth.clamp(resultIndex, 0, draft.results.size() - 1));
    }

    /** Whether a step is the spout, which applies a fluid rather than an item. */
    private static boolean isFillingStep(RecipeDraft step) {
        return "create:filling".equals(step.createType);
    }

    /** The amount on a filling step's fluid, or a bucket while it has none yet. */
    private static int stepAmount(RecipeDraft step) {
        IngredientValue value = step.input(0);
        return value.isFluid() ? value.amount() : IngredientValue.BUCKET;
    }

    /** The same fluid with a different quantity; anything that is not a fluid is left alone. */
    private static IngredientValue withAmount(IngredientValue value, int amount) {
        if (!value.isFluid()) {
            return value;
        }
        return value.isFluidTag()
                ? IngredientValue.fluidTag(value.id(), Math.max(1, amount))
                : IngredientValue.fluid(value.id(), Math.max(1, amount));
    }

    /**
     * What the text in a step's field means, decided by the step's own type rather than by the text: a
     * filling step names a fluid, every other step names an item, and a leading {@code #} is a tag of
     * whichever of the two it is.
     */
    private IngredientValue valueFor(RecipeDraft step, String raw, int amount) {
        String typed = raw.trim();
        boolean tagged = typed.startsWith("#");
        Identifier parsed = Identifier.tryParse(tagged ? typed.substring(1) : typed);
        if (parsed == null) {
            return IngredientValue.empty();
        }
        if (isFillingStep(step)) {
            return tagged ? IngredientValue.fluidTag(parsed, amount) : IngredientValue.fluid(parsed, amount);
        }
        return tagged ? IngredientValue.tag(parsed) : IngredientValue.item(parsed);
    }

    private static RecipeDraft blankStep() {
        RecipeDraft step = RecipeDraft.blank(RecipeDraft.Kind.CREATE_PROCESSING);
        step.createType = STEP_TYPES[0];
        return step;
    }

    /** The next step type, or the previous one for {@code by == -1}, wrapping either way round. */
    private static String nextType(String current, int by) {
        for (int i = 0; i < STEP_TYPES.length; i++) {
            if (STEP_TYPES[i].equals(current)) {
                int next = (i + by % STEP_TYPES.length + STEP_TYPES.length) % STEP_TYPES.length;
                return STEP_TYPES[next];
            }
        }
        return STEP_TYPES[0];
    }

    /** Drops the {@code create:} prefix so a step's type fits on its button. */
    private static String shortType(String type) {
        int colon = type == null ? -1 : type.indexOf(':');
        return colon < 0 ? String.valueOf(type) : type.substring(colon + 1);
    }

    private static String idOf(IngredientValue value) {
        if (value == null || value.isEmpty() || value.id() == null) {
            return "";
        }
        boolean tagged = value.isFluidTag() || value.kind() == IngredientValue.Kind.TAG;
        return (tagged ? "#" : "") + value.id();
    }

    /** A weight without its trailing zero, so a pool of whole numbers reads as whole numbers. */
    private static String trimFloat(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** An item id, or a tag when the text starts with {@code #}. */
    private static IngredientValue itemOrTagOf(String raw) {
        String typed = raw.trim();
        if (!typed.startsWith("#")) {
            return itemOf(typed);
        }
        Identifier parsed = Identifier.tryParse(typed.substring(1));
        return parsed == null ? IngredientValue.empty() : IngredientValue.tag(parsed);
    }

    private static IngredientValue itemOf(String raw) {
        Identifier parsed = Identifier.tryParse(raw.trim());
        return parsed == null ? IngredientValue.empty() : IngredientValue.item(parsed);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void save() {
        Identifier id = Identifier.tryParse(idValue);
        if (id == null) {
            status.set(Component.translatable("sce.status.invalid_id"));
            return;
        }
        // Say what is missing instead of sending a recipe the server can only reject.
        if (draft.input(0).isEmpty() || draft.transitionalItem.isEmpty() || !hasAnyResult()) {
            status.set(Component.translatable("sce.status.sequence_incomplete"));
            return;
        }
        if (draft.sequence.isEmpty()) {
            status.set(Component.translatable("sce.status.sequence_no_steps"));
            return;
        }
        JsonObject json = SequencedAssemblyCompiler.toJson(draft);
        RecipeCompiler.restoreInto(draft, json);
        SceNetworking.sendSave(id, json.toString());
        status.set(Component.translatable("sce.status.saving", id.toString()));
    }

    /** Whether the pool has anything in it at all; an entry left blank is dropped when it is written. */
    private boolean hasAnyResult() {
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item != null && !entry.item.isEmpty()) {
                return true;
            }
        }
        return false;
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (fields.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        int left = width / 2 - 155;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && mouseX >= left && mouseX < left + 310
                && mouseY >= ROW_TYPE && mouseY < ROW_TYPE + 16) {
            playClick();
            SceNetworking.sendOpenEditor(idValue, RecipeModes.previousAvailable(sequenceMode()));
            return true;
        }
        // Every other cycling button in this editor walks backwards on a right-click. These did not,
        // which read as the one place where the rule did not hold rather than as a deliberate exception.
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && mouseX >= left + 20 && mouseX < left + 112) {
            int visible = visibleSteps();
            for (int row = 0; row < visible && scroll + row < draft.sequence.size(); row++) {
                int y = STEP_TOP + row * STEP_HEIGHT;
                if (mouseY >= y && mouseY < y + 20) {
                    RecipeDraft step = draft.sequence.get(scroll + row);
                    step.createType = nextType(step.createType, -1);
                    playClick();
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Buttons click when pressed; a right-click handled by hand has to say so itself. */
    private void playClick() {
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (fields.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (fields.mouseScrolled(scrollY)) {
            return true;
        }
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0,
                Math.max(0, draft.sequence.size() - visibleSteps()));
        rebuildWidgets();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // super.render draws the blurred background and the widgets; labels go after it or that blur
        // would smear them (same ordering as the hub screen).
        // Before the widgets draw: what this decides is read by the fields as they render.
        fields.update(mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

        label(graphics, "sce.sequence.label_id", left, ROW_ID);
        label(graphics, "sce.sequence.label_base", left, ROW_PARTS);
        label(graphics, "sce.sequence.label_transitional", left + 136, ROW_PARTS);
        label(graphics, "sce.sequence.label_loops", left + 272, ROW_PARTS);
        label(graphics, "sce.sequence.label_result", left, ROW_RESULT);
        label(graphics, "sce.sequence.label_count", left + 154, ROW_RESULT);
        label(graphics, "sce.sequence.label_weight", left + 190, ROW_RESULT);
        graphics.text(font, Component.translatable("sce.sequence.steps"), left, STEPS_HEADER, 0xFFFFFFFF);

        int visible = visibleSteps();
        for (int row = 0; row < visible && scroll + row < draft.sequence.size(); row++) {
            graphics.text(font, (scroll + row + 1) + ".", left, STEP_TOP + row * STEP_HEIGHT + 6, 0xFFD0D0D0);
        }
        status.drawCentered(graphics, font, width / 2, height - 40);
        fields.render(graphics, font);
    }

    /** A caption sat just above its field, so an empty form still says what each box is for. */
    private void label(GuiGraphicsExtractor graphics, String key, int x, int fieldY) {
        graphics.text(font, Component.translatable(key), x, fieldY - 10, 0xFFFFFFFF, true);
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
            minecraft.setScreenAndShow(new RecipeManagerScreen());
            return;
        }
        status.set(Component.translatable("sce.status.save_failed", id.toString()));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
