package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.client.CobblemonSeasonings;
import org.mateof24.sce.core.edit.CookingPot;
import org.mateof24.sce.menu.EditorLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a campfire pot recipe's seasoning rules are set: the item tag a player may add, and which of the
 * seven properties the finished dish absorbs from it.
 *
 * <p>A screen of its own rather than a panel over the editor. The editor is a container screen, and a
 * container screen paints its own background, its slots and the ghosts of the recipe's items from inside
 * {@code renderBackground} — so anything laid over it still has the editor showing through. Stepping
 * away to another screen is the only way for nothing of it to remain, and it is also what this is: a
 * second window, not a lid.
 *
 * <p>Nothing here is sent anywhere. It hands the two values back to the editor on the way out, and the
 * editor writes them when the recipe is saved.
 */
@Environment(EnvType.CLIENT)
public class SeasoningScreen extends Screen {
    /** Only a width and a height to lay things out in; nothing is drawn behind them. */
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 206;
    /** The same air above the warning as below it, so it reads as one block between two rows. */
    private static final int BLOCK_GAP = 12;
    private static final int LINE = 10;
    /** Room kept for the warning whether or not it is showing, so the Done button never moves. */
    private static final int WARNING_LINES = 2;

    private final RecipeEditorScreen parent;
    private final List<String> absorbs = new ArrayList<>();
    private final List<Button> absorbButtons = new ArrayList<>();
    private final FieldAssist fields = new FieldAssist();

    private String tag;
    private int left;
    private int top;

    public SeasoningScreen(RecipeEditorScreen parent, String tag, List<String> absorbs) {
        super(Component.translatable("sce.pot.seasoning_title"));
        this.parent = parent;
        this.tag = CookingPot.seasoningTag(tag);
        this.absorbs.addAll(absorbs);
    }

    // ------------------------------------------------------------------ where everything sits

    private int slotsY() {
        return top + 26;
    }

    private int tagRowY() {
        return top + 60;
    }

    private int absorbRowY(int row) {
        return top + 94 + row * 20;
    }

    private int warningY() {
        return absorbRowY(1) + 16 + BLOCK_GAP;
    }

    private int doneY() {
        return warningY() + WARNING_LINES * LINE + BLOCK_GAP;
    }

    /** What the tag admits, narrowed to what would actually do something, as Cobblemon's viewer does. */
    private List<ItemStack> preview() {
        return TagCycle.window(Identifier.tryParse(CookingPot.seasoningTag(tag)),
                CookingPot.SEASONING_SLOTS, stack -> CobblemonSeasonings.contributes(stack, absorbs));
    }

    private int slotX(int index) {
        return left + PANEL_WIDTH / 2 - (CookingPot.SEASONING_SLOTS * EditorLayout.SLOT) / 2
                + index * EditorLayout.SLOT + 1;
    }

    @Override
    protected void init() {
        TagCycle.forget();
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        fields.clear();
        absorbButtons.clear();

        EditBox tagBox = new EditBox(font, left + 10, tagRowY(), PANEL_WIDTH - 20, 16,
                Component.translatable("sce.hint.seasoning_tag"));
        tagBox.setMaxLength(200);
        tagBox.setValue(tag);
        tagBox.setHint(Component.translatable("sce.hint.seasoning_tag"));
        tagBox.setResponder(value -> tag = value);
        addRenderableWidget(tagBox);
        fields.add(tagBox, FieldAssist.id(), FieldAssist.Source.ITEM_TAGS);
        setInitialFocus(tagBox);

        for (int i = 0; i < CookingPot.PROCESSORS.size(); i++) {
            String processor = CookingPot.PROCESSORS.get(i);
            AbsorbButton button = new AbsorbButton(left + 10 + (i % 4) * 58, absorbRowY(i / 4),
                    Component.translatable("sce.absorb." + processor));
            // One that is on is drawn the way the game draws a button you cannot press, which reads as
            // "already done" at a glance. Its clicks are taken by hand for that very reason.
            button.active = !absorbs.contains(processor);
            absorbButtons.add(addRenderableWidget(button));
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + PANEL_WIDTH / 2 - 32, doneY(), 64, 20).build());
    }

    @Override
    public void onClose() {
        parent.applySeasoning(tag, absorbs);
        minecraft.setScreen(parent);
    }

    private void toggle(String processor) {
        if (!absorbs.remove(processor)) {
            absorbs.add(processor);
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 0 && fields.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        // The ones that are on are inactive widgets and would take no click of their own, so the seven
        // are read here first, by where they are.
        for (int i = 0; i < absorbButtons.size() && i < CookingPot.PROCESSORS.size(); i++) {
            Button candidate = absorbButtons.get(i);
            if (button == 0 && mouseX >= candidate.getX() && mouseX < candidate.getX() + candidate.getWidth()
                    && mouseY >= candidate.getY() && mouseY < candidate.getY() + candidate.getHeight()) {
                minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                toggle(CookingPot.PROCESSORS.get(i));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        // While the completion list is open it owns the arrows, tab, enter and escape.
        if (fields.keyPressed(key)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return fields.mouseScrolled(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        fields.update(mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, left + PANEL_WIDTH / 2, top + 12, 0xFFFFFF);
        List<ItemStack> preview = preview();
        for (int i = 0; i < CookingPot.SEASONING_SLOTS; i++) {
            int x = slotX(i);
            graphics.blit(RenderPipelines.GUI_TEXTURED, RecipeEditorScreen.SLOT_TEXTURE,
                    x - 1, slotsY() - 1, 0.0F, 0.0F, 18, 18, 18, 18);
            if (i < preview.size()) {
                graphics.renderFakeItem(preview.get(i), x, slotsY());
            }
        }
        graphics.drawString(font, Component.translatable("sce.label.seasoning_tag"),
                left + 10, tagRowY() - 10, 0xFFFFFF, true);
        graphics.drawString(font, Component.translatable("sce.label.absorbs"),
                left + 10, absorbRowY(0) - 10, 0xFFFFFF, true);

        if (!CookingPot.seasoningIsCoherent(tag, absorbs)) {
            // Neither half fails loudly on its own, so this is the only place the author will hear it.
            List<FormattedCharSequence> lines =
                    font.split(Component.translatable("sce.pot.seasoning_half_done"), PANEL_WIDTH - 20);
            int y = warningY() + Math.max(0, WARNING_LINES - lines.size()) * LINE / 2;
            for (FormattedCharSequence line : lines) {
                graphics.drawCenteredString(font, line, left + PANEL_WIDTH / 2, y, 0xFFAA00);
                y += LINE;
            }
        }

        fields.render(graphics, font);
        renderTooltips(graphics, mouseX, mouseY);
    }

    /** Drawn by hand: the widgets' own tooltips belong to a pass this screen does not use. */
    private void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ItemStack> preview = preview();
        for (int i = 0; i < preview.size(); i++) {
            int x = slotX(i);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= slotsY() && mouseY < slotsY() + 16) {
                ItemStack stack = preview.get(i);
                graphics.setTooltipForNextFrame(font, Screen.getTooltipFromItem(minecraft, stack),
                        stack.getTooltipImage(), mouseX, mouseY);
                return;
            }
        }
        for (int i = 0; i < absorbButtons.size() && i < CookingPot.PROCESSORS.size(); i++) {
            if (absorbButtons.get(i).isMouseOver(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font,
                        Component.translatable("sce.tooltip.absorb." + CookingPot.PROCESSORS.get(i)),
                        mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A button whose "on" state is the one the game already has for a button that cannot be pressed.
     *
     * <p>Inactive is a look the player knows and it needs no frame drawn around it; the only thing it
     * lacks is a way to tell it from a button that is genuinely unavailable, which the green lettering
     * supplies.
     */
    private static final class AbsorbButton extends Button.Plain {
        private AbsorbButton(int x, int y, Component message) {
            super(x, y, 54, 16, message, b -> {
            }, DEFAULT_NARRATION);
        }

        @Override
        protected void renderScrollingStringOverContents(ActiveTextCollector collector, Component message,
                                                         int colour) {
            // The colour the button worked out, with the grey of an unpressable one swapped for green
            // and its alpha kept, so the fade the widget applies still applies.
            super.renderScrollingStringOverContents(collector, message,
                    active ? colour : 0x55FF55 | (colour & 0xFF000000));
        }
    }
}
