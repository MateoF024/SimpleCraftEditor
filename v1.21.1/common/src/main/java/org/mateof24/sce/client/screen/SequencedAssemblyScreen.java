package org.mateof24.sce.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.edit.SequencedAssemblyCompiler;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for Create's sequenced assembly, which is the one recipe that is not a single recipe: a base
 * ingredient is carried through an ordered list of processing steps — each a recipe of its own — looping a
 * number of times before yielding the results. That does not fit the shared slot layout, so it gets a
 * screen of its own: the header holds the base, transitional item and loop count, and below it the steps
 * are listed and can be added, retyped, refilled and removed in place.
 *
 * <p>There is no inventory or recipe viewer here to drag items from, so every id field completes against
 * the live item registry ({@link ItemIdSuggestions}).
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
    private final ItemIdSuggestions suggestions = new ItemIdSuggestions();
    private final List<EditBox> idFields = new ArrayList<>();

    private String idValue;
    private Component status = Component.empty();
    private int scroll;

    public SequencedAssemblyScreen(ResourceLocation id, String json) {
        super(Component.translatable("sce.sequence.title"));
        this.idValue = id.toString();
        this.draft = parse(id, json);
    }

    private static RecipeDraft parse(ResourceLocation id, String json) {
        if (!json.isEmpty()) {
            try {
                return SequencedAssemblyCompiler.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
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
        idFields.clear();
        int left = width / 2 - 155;

        addRenderableWidget(Button.builder(Component.translatable("sce.button.type",
                        Component.translatable(RecipeModes.labelKey(sequenceMode()))), b ->
                        SceNetworking.sendOpenEditor(idValue, RecipeModes.nextAvailable(sequenceMode())))
                .bounds(left, ROW_TYPE, 310, 16).build());

        textBox(left, ROW_ID, 250, idValue, s -> idValue = s, "sce.hint.id", false);
        addRenderableWidget(Button.builder(Component.translatable("sce.button.load"), b ->
                        SceNetworking.sendOpenEditor(idValue, -1))
                .bounds(left + 256, ROW_ID, 54, 16).build());

        textBox(left, ROW_PARTS, 130, idOf(draft.input(0)),
                s -> draft.setInput(0, itemOf(s)), "sce.hint.sequence_base", true);
        textBox(left + 136, ROW_PARTS, 130, idOf(draft.transitionalItem),
                s -> draft.transitionalItem = itemOf(s), "sce.hint.sequence_transitional", true);
        textBox(left + 272, ROW_PARTS, 38, Integer.toString(draft.loops),
                s -> draft.loops = Math.max(1, parseInt(s, draft.loops)), "sce.hint.sequence_loops", false);

        RecipeDraft.ResultEntry result = firstResult();
        textBox(left, ROW_RESULT, 250, idOf(result.item),
                s -> result.item = itemOf(s), "sce.hint.sequence_result", true);
        textBox(left + 256, ROW_RESULT, 54, Integer.toString(result.count),
                s -> result.count = Math.max(1, parseInt(s, result.count)), "sce.hint.amount", false);

        addRenderableWidget(Button.builder(Component.translatable("sce.button.add_step"), b -> {
            draft.sequence.add(blankStep());
            scroll = Math.max(0, draft.sequence.size() - visibleSteps());
            rebuildWidgets();
        }).bounds(left + 210, STEPS_HEADER - 4, 100, 20).build());

        int visible = visibleSteps();
        scroll = Mth.clamp(scroll, 0, Math.max(0, draft.sequence.size() - visible));
        for (int row = 0; row < visible && scroll + row < draft.sequence.size(); row++) {
            int index = scroll + row;
            RecipeDraft step = draft.sequence.get(index);
            int y = STEP_TOP + row * STEP_HEIGHT;
            addRenderableWidget(Button.builder(Component.literal(shortType(step.createType)), b -> {
                step.createType = nextType(step.createType);
                rebuildWidgets();
            }).bounds(left + 20, y, 92, 20).build());
            textBox(left + 116, y + 2, 150, idOf(step.input(0)),
                    s -> step.setInput(0, itemOf(s)), "sce.hint.sequence_step_item", true);
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                draft.sequence.remove(index);
                rebuildWidgets();
            }).bounds(left + 272, y, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.save"), b -> save())
                .bounds(left, height - 26, 100, 20).build());
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
                            String hintKey, boolean completesItems) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.translatable(hintKey));
        box.setMaxLength(200);
        box.setValue(value);
        box.setResponder(onChange);
        addRenderableWidget(box);
        if (completesItems) {
            idFields.add(box);
        }
        return box;
    }

    private RecipeDraft.ResultEntry firstResult() {
        if (draft.results.isEmpty()) {
            draft.results.add(new RecipeDraft.ResultEntry(IngredientValue.empty(), 1, 1.0f));
        }
        return draft.results.get(0);
    }

    private static RecipeDraft blankStep() {
        RecipeDraft step = RecipeDraft.blank(RecipeDraft.Kind.CREATE_PROCESSING);
        step.createType = STEP_TYPES[0];
        return step;
    }

    private static String nextType(String current) {
        for (int i = 0; i < STEP_TYPES.length; i++) {
            if (STEP_TYPES[i].equals(current)) {
                return STEP_TYPES[(i + 1) % STEP_TYPES.length];
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
        return value == null || value.isEmpty() ? "" : value.id().toString();
    }

    private static IngredientValue itemOf(String raw) {
        ResourceLocation parsed = ResourceLocation.tryParse(raw.trim());
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
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            status = Component.translatable("sce.status.invalid_id");
            return;
        }
        JsonObject json = SequencedAssemblyCompiler.toJson(draft);
        SceNetworking.sendSave(id, json.toString());
        status = Component.translatable("sce.status.saving", id.toString());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (suggestions.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        int left = width / 2 - 155;
        if (button == 1 && mouseX >= left && mouseX < left + 310
                && mouseY >= ROW_TYPE && mouseY < ROW_TYPE + 16) {
            playClick();
            SceNetworking.sendOpenEditor(idValue, RecipeModes.previousAvailable(sequenceMode()));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Buttons click when pressed; a right-click handled by hand has to say so itself. */
    private void playClick() {
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB && suggestions.acceptFirst()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0,
                Math.max(0, draft.sequence.size() - visibleSteps()));
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // super.render draws the blurred background and the widgets; labels go after it or that blur
        // would smear them (same ordering as the hub screen).
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        label(graphics, "sce.sequence.label_id", left, ROW_ID);
        label(graphics, "sce.sequence.label_base", left, ROW_PARTS);
        label(graphics, "sce.sequence.label_transitional", left + 136, ROW_PARTS);
        label(graphics, "sce.sequence.label_loops", left + 272, ROW_PARTS);
        label(graphics, "sce.sequence.label_result", left, ROW_RESULT);
        label(graphics, "sce.sequence.label_count", left + 256, ROW_RESULT);
        graphics.drawString(font, Component.translatable("sce.sequence.steps"), left, STEPS_HEADER, 0xFFFFFF);

        int visible = visibleSteps();
        for (int row = 0; row < visible && scroll + row < draft.sequence.size(); row++) {
            graphics.drawString(font, (scroll + row + 1) + ".", left, STEP_TOP + row * STEP_HEIGHT + 6, 0xD0D0D0);
        }
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 40, 0xE0E070);
        }
        suggestions.update(idFields);
        suggestions.render(graphics, font);
    }

    /** A caption sat just above its field, so an empty form still says what each box is for. */
    private void label(GuiGraphics graphics, String key, int x, int fieldY) {
        graphics.drawString(font, Component.translatable(key), x, fieldY - 10, 0xFFFFFF, true);
    }

    /** Called from the network layer with the server's verdict on a save request. */
    public void onSaveResult(ResourceLocation id, boolean ok) {
        status = Component.translatable(ok ? "sce.status.saved" : "sce.status.save_failed", id.toString());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
