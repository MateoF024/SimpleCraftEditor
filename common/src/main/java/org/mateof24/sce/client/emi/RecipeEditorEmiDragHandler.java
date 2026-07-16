package org.mateof24.sce.client.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.List;

/** Lets items be dragged straight from EMI's list onto the editor's recipe slots. */
@Environment(EnvType.CLIENT)
public class RecipeEditorEmiDragHandler implements EmiDragDropHandler<RecipeEditorScreen> {
    @Override
    public boolean dropStack(RecipeEditorScreen screen, EmiIngredient dragged, int mouseX, int mouseY) {
        ItemStack stack = firstItem(dragged);
        if (stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            if (contains(screen.inputSlotArea(i), mouseX, mouseY)) {
                screen.setGhostInput(i, stack);
                return true;
            }
        }
        if (contains(screen.outputSlotArea(), mouseX, mouseY)) {
            screen.setGhostOutput(stack);
            return true;
        }
        return false;
    }

    @Override
    public void render(RecipeEditorScreen screen, EmiIngredient dragged, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (firstItem(dragged).isEmpty()) {
            return;
        }
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            highlight(graphics, screen.inputSlotArea(i));
        }
        highlight(graphics, screen.outputSlotArea());
    }

    private static void highlight(GuiGraphics graphics, Rect2i area) {
        graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + area.getHeight(), 0x6655FF55);
    }

    private static boolean contains(Rect2i area, int mouseX, int mouseY) {
        return mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    private static ItemStack firstItem(EmiIngredient ingredient) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).getItemStack();
    }
}
