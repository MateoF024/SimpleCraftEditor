package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.core.anvil.AnvilRule;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the anvil's repair rules are written: the rules in force, and under a line the one being written.
 *
 * <p>A screen rather than a mode of the recipe editor, because a repair rule is not a recipe. Nothing
 * about it lives in the recipe manager, it has no ingredients and no result, and giving it slots would
 * say it was one.
 *
 * <p><b>There is nothing to save.</b> A change goes when the player has finished making it — when a field
 * is left, when a mode is turned, when a rule is added or removed — and however the screen goes away,
 * the button or Escape or anything else, whatever is still outstanding goes with it. Holding an edit
 * until one particular button is pressed only means losing it to the one key every player presses to
 * leave a screen.
 *
 * <p><b>The list holds rules; the row under it holds what is not one yet.</b> Adding used to drop a blank
 * row into the list, which asked the player to believe an empty rule was a rule and left them wondering
 * whether anything had happened. They are two places now: below the line is what is being written, and
 * the button beside it — dark until both sides are filled in — moves it up into the list, where it gets
 * a remove button like every other rule.
 */
@Environment(EnvType.CLIENT)
public class AnvilRulesScreen extends Screen {
    private static final int PANEL_WIDTH = 358;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 46;
    /** Room kept under the list for the line, the row being written, the message and the button. */
    private static final int FOOTER = 96;

    private static final int TARGET_WIDTH = 110;
    private static final int MATERIAL_WIDTH = 110;
    private static final int MODE_WIDTH = 66;
    /**
     * The column at the end of a row. It is as wide as the button that adds a rule, and the one that
     * removes a rule sits at its right edge: a cross does not need the room, and a ragged edge down the
     * side of the list would be the only thing it gained by taking it.
     */
    private static final int TAIL_WIDTH = 36;
    private static final int REMOVE_WIDTH = 18;
    private static final int ARROW_WIDTH = 12;
    private static final int GAP = 6;

    /**
     * One row being edited. The text is held here rather than in the boxes because the boxes are thrown
     * away and rebuilt whenever the list changes shape, and a value living only in a widget would go
     * with it.
     */
    private static final class Row {
        private String target;
        private String material;
        private AnvilRule.Mode mode;

        Row(AnvilRule rule) {
            target = rule.target();
            material = rule.material();
            mode = rule.mode();
        }

        Row() {
            this(new AnvilRule("", "", AnvilRule.Mode.REPLACE));
        }

        void clear() {
            target = "";
            material = "";
            mode = AnvilRule.Mode.REPLACE;
        }

        AnvilRule toRule() {
            return new AnvilRule(target.trim(), material.trim(), mode);
        }

        boolean isComplete() {
            return toRule().isComplete();
        }
    }

    private final List<Row> rows = new ArrayList<>();
    /**
     * The rule being written. Kept between openings, because closing a screen is not a decision to throw
     * away what was half typed into it — and half a rule is the one thing here that is not stored.
     */
    private static final Row draft = new Row();

    /**
     * The mode button of each row on screen, and the row it belongs to, so a right-click can find one.
     * There are only two modes, so stepping back lands where stepping forward does - but every cycling
     * button in this mod answers a right-click, and one that silently does not is the one that makes a
     * player stop trying it on the others.
     */
    private final Map<Button, Row> modeButtons = new LinkedHashMap<>();
    private final FieldAssist fields = new FieldAssist();
    private final StatusLine status = new StatusLine();
    /** The bar down the right of the list: where the list is, and the handle for moving it. */
    private final ScrollBar bar = new ScrollBar();

    /** Where Escape and Done both lead, which is where this screen was opened from. */
    private final Screen parent;
    /** What the server was last told, so that leaving a field untouched sends nothing. */
    private List<AnvilRule> lastSent;
    /** The box being typed in, so that leaving one can mean the change in it is finished. */
    private EditBox lastFocused;
    private EditBox draftTarget;
    private EditBox draftMaterial;
    private Button addButton;

    private int left;

    public AnvilRulesScreen(Screen parent) {
        super(Component.translatable("sce.anvil.title"));
        this.parent = parent;
        for (AnvilRule rule : AnvilRules.INSTANCE.rules()) {
            rows.add(new Row(rule));
        }
        lastSent = complete();
    }

    /** How many rows fit between the captions and the line. */
    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - FOOTER) / ROW_HEIGHT);
    }

    private int materialX() {
        return left + TARGET_WIDTH + GAP + ARROW_WIDTH + GAP;
    }

    private int modeX() {
        return materialX() + MATERIAL_WIDTH + GAP;
    }

    private int tailX() {
        return modeX() + MODE_WIDTH + GAP;
    }

    /** The line the list stops at, and under which the rule being written sits. */
    private int dividerY() {
        return height - 90;
    }

    private int draftY() {
        return height - 70;
    }

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        fields.clear();
        modeButtons.clear();
        // The bottom as well as the sides: the row being written is near the foot of the screen, and a
        // completion list opening downwards from there would open off the edge of it.
        fields.limits(left, left + PANEL_WIDTH, height - 4);
        bar.place(left + PANEL_WIDTH + ScrollBar.GAP, LIST_TOP, ROW_HEIGHT, visibleRows(), rows.size());

        for (int i = 0; i < visibleRows() && bar.scroll() + i < rows.size(); i++) {
            buildRow(rows.get(bar.scroll() + i), LIST_TOP + i * ROW_HEIGHT);
        }
        buildDraft();

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + (PANEL_WIDTH - 110) / 2, height - 30, 110, 20).build());
    }

    /** One rule of the list: the two sides, the mode, and the button that takes it away. */
    private void buildRow(Row row, int y) {
        field(left, y, TARGET_WIDTH, row, true);
        field(materialX(), y, MATERIAL_WIDTH, row, false);
        modeButton(modeX(), y, row);
        addRenderableWidget(Button.builder(Component.literal("x"), b -> {
            rows.remove(row);
            rebuildWidgets();
            apply("sce.anvil.removed");
        }).bounds(tailX() + TAIL_WIDTH - REMOVE_WIDTH, y - 1, REMOVE_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("sce.tooltip.anvil_remove"))).build());
    }

    /**
     * The row under the line: the same columns as a rule, so that what it is about to become is plain,
     * and the button that makes it one.
     */
    private void buildDraft() {
        int y = draftY();
        draftTarget = field(left, y, TARGET_WIDTH, draft, true);
        draftMaterial = field(materialX(), y, MATERIAL_WIDTH, draft, false);
        modeButton(modeX(), y, draft);
        addButton = addRenderableWidget(Button.builder(
                Component.translatable("sce.anvil.add"), b -> addDraft())
                .bounds(tailX(), y - 1, TAIL_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("sce.tooltip.anvil_new"))).build());
        refreshAdd();
    }

    /** One of the two id fields of a row, wherever that row is. */
    private EditBox field(int x, int y, int w, Row row, boolean isTarget) {
        String label = isTarget ? "sce.anvil.label_target" : "sce.anvil.label_material";
        EditBox box = new EditBox(font, x, y, w, 18, Component.translatable(label));
        box.setMaxLength(200);
        box.setValue(isTarget ? row.target : row.material);
        box.setHint(FieldAssist.hint("sce.anvil.hint_item"));
        box.setResponder(s -> {
            if (isTarget) {
                row.target = s;
            } else {
                row.material = s;
            }
            refreshAdd();
        });
        addRenderableWidget(box);
        // On the left, only the items an anvil can mend. Offering the rest would be offering rules that
        // can never fire: the menu asks nothing at all about an item with no durability.
        fields.add(box, FieldAssist.idOrTag(),
                isTarget ? FieldAssist.Source.REPAIRABLE_OR_TAGS : FieldAssist.Source.ITEMS_OR_TAGS);
        return box;
    }

    private void modeButton(int x, int y, Row row) {
        modeButtons.put(addRenderableWidget(Button.builder(
                        Component.translatable("sce.anvil.mode." + row.mode.key()), b -> cycleMode(row))
                .bounds(x, y - 1, MODE_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("sce.tooltip.anvil_mode"))).build()), row);
    }

    private void cycleMode(Row row) {
        row.mode = row.mode == AnvilRule.Mode.REPLACE ? AnvilRule.Mode.ADD : AnvilRule.Mode.REPLACE;
        rebuildWidgets();
        // Turning the mode of the row being written changes nothing the anvil can see yet.
        if (row != draft) {
            apply("sce.anvil.applied");
        }
    }

    /** Dark until there is something to add: a rule with a side missing could never fire. */
    private void refreshAdd() {
        if (addButton != null) {
            addButton.active = draft.isComplete();
        }
    }

    /** Moves what is being written up into the list, where it becomes a rule like the others. */
    private void addDraft() {
        if (!draft.isComplete()) {
            return;
        }
        rows.add(new Row(draft.toRule()));
        draft.clear();
        // Straight to the new rule, which is at the bottom: adding something you then have to go
        // looking for is not adding it. Placed first so the bar knows the list just grew.
        bar.place(left + PANEL_WIDTH + ScrollBar.GAP, LIST_TOP, ROW_HEIGHT, visibleRows(), rows.size());
        bar.setScroll(rows.size());
        rebuildWidgets();
        apply("sce.anvil.added");
    }

    /**
     * Sends the rules as they stand and says so, unless they are already what the server has.
     *
     * <p>The whole list goes at once, which is also how it is stored: a rule added and then removed
     * leaves no trace, and the file cannot end up holding half an edit. Rows nobody finished filling in
     * are left out rather than stored, because a rule with a side missing matches nothing and would sit
     * in the file forever saying so.
     */
    private boolean apply(String messageKey) {
        List<AnvilRule> complete = complete();
        if (complete.equals(lastSent)) {
            return false;
        }
        lastSent = complete;
        SceNetworking.sendAnvilRules(complete);
        status.set(message(messageKey));
        return true;
    }

    private List<AnvilRule> complete() {
        List<AnvilRule> complete = new ArrayList<>(rows.size());
        for (Row row : rows) {
            AnvilRule rule = row.toRule();
            if (rule.isComplete()) {
                complete.add(rule);
            }
        }
        return complete;
    }

    /**
     * What just happened, and what it will not yet have changed.
     *
     * <p>The anvil obeys as soon as the server has the rules, and JEI is told at once. EMI and REI build
     * their lists when their plugins are registered and offer no way in afterwards, so if either is
     * installed the player is told rather than left wondering why the viewer disagrees with the block.
     */
    private static Component message(String key) {
        Component text = Component.translatable(key);
        if (dev.architectury.platform.Platform.isModLoaded("emi")
                || dev.architectury.platform.Platform.isModLoaded("roughlyenoughitems")) {
            return Component.empty().append(text).append(" ")
                    .append(Component.translatable("sce.anvil.needs_reload"));
        }
        return text;
    }

    /**
     * A field that has stopped being typed in is a change the player has finished making, so that is
     * when it goes. Watching where the focus is is how a text box says it is done without being asked.
     */
    @Override
    public void tick() {
        super.tick();
        EditBox focused = focusedBox();
        if (focused != lastFocused) {
            lastFocused = focused;
            apply("sce.anvil.applied");
        }
    }

    private EditBox focusedBox() {
        for (GuiEventListener child : children()) {
            if (child instanceof EditBox box && box.isFocused()) {
                return box;
            }
        }
        return null;
    }

    /**
     * The last chance to send, and it runs however the screen goes away — which is the point. Escape is
     * not a decision to discard anything.
     */
    @Override
    public void removed() {
        if (apply("sce.anvil.applied")) {
            // Something was still outstanding, so the player never saw it go. This screen is already
            // on its way out and cannot say so; the one arriving has not been built yet, and can.
            RecipeManagerScreen.showOnOpen(message("sce.anvil.applied"));
        }
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // An open completion list is the thing under the pointer, so it gets the wheel before the list
        // of rules behind it does.
        if (fields.mouseScrolled(deltaY)) {
            return true;
        }
        if (bar.visible()) {
            if (bar.setScroll(bar.scroll() - (int) Math.signum(deltaY))) {
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.mouseDragged(event.y())) {
            rebuildWidgets();
        }
        return bar.dragging() || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        bar.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // While the completion list is open it owns the arrows, tab, enter and escape.
        if (fields.keyPressed(event.key())) {
            return true;
        }
        // Enter, from either side of the row being written, is the same as pressing the button at the
        // end of it: a row you have just finished typing is a row you are finished with.
        int key = event.key();
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && inDraft()) {
            addDraft();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean inDraft() {
        return (draftTarget != null && draftTarget.isFocused())
                || (draftMaterial != null && draftMaterial.isFocused());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 0 && fields.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (bar.mouseClicked(mouseX, mouseY, button)) {
            rebuildWidgets();
            return true;
        }
        if (button == 1) {
            for (Map.Entry<Button, Row> entry : modeButtons.entrySet()) {
                if (entry.getKey().isMouseOver(mouseX, mouseY)) {
                    // Buttons click when pressed; a right-click handled by hand has to say so itself.
                    minecraft.getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    cycleMode(entry.getValue());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        fields.update(mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);

        // White with a shadow, like the title and like every caption on a screen with no panel behind
        // it: grey over a view of the world is a colour that depends on what the player is standing in
        // front of. And only when there are columns to caption - naming them over an empty space says
        // there is something there.
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("sce.anvil.empty"),
                    width / 2, LIST_TOP + 6, 0xFF909090);
        } else {
            int captionY = LIST_TOP - 12;
            graphics.drawString(font, Component.translatable("sce.anvil.label_target"), left, captionY,
                    0xFFFFFFFF, true);
            graphics.drawString(font, Component.translatable("sce.anvil.label_material"), materialX(),
                    captionY, 0xFFFFFFFF, true);
        }
        for (int i = 0; i < visibleRows() && bar.scroll() + i < rows.size(); i++) {
            arrow(graphics, LIST_TOP + i * ROW_HEIGHT + 5);
        }

        // Where the list stops being the list. Above the line are the rules the anvil is following;
        // below it, the one that is not a rule yet.
        graphics.fill(left, dividerY(), left + PANEL_WIDTH, dividerY() + 1, 0x40FFFFFF);
        graphics.drawString(font, Component.translatable("sce.anvil.new"), left, dividerY() + 6,
                0xFFFFFFFF, true);
        arrow(graphics, draftY() + 5);

        bar.draw(graphics);
        bar.drawCount(graphics, font, left + PANEL_WIDTH, LIST_TOP - 12);
        fields.render(graphics, font);
        status.drawCentered(graphics, font, width / 2, height - 44);
    }

    /** Between the two boxes, level with the text in them, so a row reads as one sentence. */
    private void arrow(GuiGraphics graphics, int y) {
        graphics.drawString(font, "→", left + TARGET_WIDTH + GAP + 2, y, 0xFFFFFFFF, false);
    }
}
