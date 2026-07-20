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
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.SequencedAssemblyCompiler;
import org.mateof24.sce.net.SceNetworking;

/**
 * Editor for Create's sequenced assembly, which is the one recipe that is not a single recipe: a base
 * ingredient is carried through an ordered list of processing steps — each a recipe of its own — looping a
 * number of times before yielding the results. That does not fit the shared slot layout, so it gets a
 * screen of its own: the header holds the base, transitional item and loop count, and below it the steps
 * are listed and can be added, retyped, refilled and removed in place.
 */
@Environment(EnvType.CLIENT)
public class SequencedAssemblyScreen extends BaseSceScreen {
    /** Processing types Create accepts as a step; cycled through by each step's type button. */
    private static final String[] STEP_TYPES = {
            "create:deploying", "create:pressing", "create:cutting", "create:filling"};

    private static final int STEP_TOP = 96;
    private static final int STEP_HEIGHT = 22;
    private static final int MAX_VISIBLE_STEPS = 5;

    private final ResourceLocation recipeId;
    private final RecipeDraft draft;

    private EditBox idBox;
    private EditBox baseBox;
    private EditBox transitionalBox;
    private EditBox loopsBox;
    private EditBox resultBox;
    private EditBox resultCountBox;

    private String idValue;
    private Component status = Component.empty();
    private int scroll;

    public SequencedAssemblyScreen(ResourceLocation id, String json) {
        super(Component.translatable("sce.sequence.title"));
        this.recipeId = id;
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

    @Override
    protected void init() {
        int left = width / 2 - 155;

        idBox = textBox(left, 28, 200, idValue, s -> idValue = s, "sce.hint.id");
        baseBox = textBox(left, 52, 130, idOf(draft.input(0)),
                s -> draft.setInput(0, itemOf(s)), "sce.hint.sequence_base");
        transitionalBox = textBox(left + 136, 52, 130, idOf(draft.transitionalItem),
                s -> draft.transitionalItem = itemOf(s), "sce.hint.sequence_transitional");
        loopsBox = textBox(left + 272, 52, 38, Integer.toString(draft.loops),
                s -> draft.loops = Math.max(1, parseInt(s, draft.loops)), "sce.hint.sequence_loops");

        RecipeDraft.ResultEntry result = firstResult();
        resultBox = textBox(left, 76, 200, idOf(result.item),
                s -> result.item = itemOf(s), "sce.hint.sequence_result");
        resultCountBox = textBox(left + 206, 76, 38, Integer.toString(result.count),
                s -> result.count = Math.max(1, parseInt(s, result.count)), "sce.hint.amount");

        scroll = Mth.clamp(scroll, 0, Math.max(0, draft.sequence.size() - MAX_VISIBLE_STEPS));
        for (int row = 0; row < MAX_VISIBLE_STEPS && scroll + row < draft.sequence.size(); row++) {
            int index = scroll + row;
            RecipeDraft step = draft.sequence.get(index);
            int y = STEP_TOP + row * STEP_HEIGHT;
            addRenderableWidget(Button.builder(Component.literal(shortType(step.createType)), b -> {
                step.createType = nextType(step.createType);
                rebuildWidgets();
            }).bounds(left + 20, y, 92, 20).build());
            addRenderableWidget(textBox(left + 116, y + 2, 150, idOf(step.input(0)),
                    s -> step.setInput(0, itemOf(s)), "sce.hint.sequence_step_item"));
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                draft.sequence.remove(index);
                rebuildWidgets();
            }).bounds(left + 272, y, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("sce.button.add_step"), b -> {
            draft.sequence.add(blankStep());
            scroll = Math.max(0, draft.sequence.size() - MAX_VISIBLE_STEPS);
            rebuildWidgets();
        }).bounds(left, STEP_TOP + MAX_VISIBLE_STEPS * STEP_HEIGHT + 4, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("sce.button.save"), b -> save())
                .bounds(left, height - 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + 210, height - 30, 100, 20).build());
    }

    private EditBox textBox(int x, int y, int w, String value, java.util.function.Consumer<String> onChange,
                            String hintKey) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.translatable(hintKey));
        box.setMaxLength(200);
        box.setValue(value);
        box.setHint(Component.translatable(hintKey));
        box.setResponder(onChange);
        return addRenderableWidget(box);
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
        ResourceLocation parsed = ResourceLocation.tryParse(raw);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0,
                Math.max(0, draft.sequence.size() - MAX_VISIBLE_STEPS));
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 155;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("sce.sequence.steps"), left, STEP_TOP - 12, 0xA0A0A0);
        for (int row = 0; row < MAX_VISIBLE_STEPS && scroll + row < draft.sequence.size(); row++) {
            graphics.drawString(font, (scroll + row + 1) + ".", left, STEP_TOP + row * STEP_HEIGHT + 6, 0xD0D0D0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 44, 0xE0E070);
        }
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
