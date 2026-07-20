package org.mateof24.sce.client.jei;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lets items be dragged straight from JEI's list onto the editor's recipe slots. */
@Environment(EnvType.CLIENT)
public class RecipeEditorGhostHandler implements IGhostIngredientHandler<RecipeEditorScreen> {
    @Override
    public <I> List<Target<I>> getTargetsTyped(RecipeEditorScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
        List<Target<I>> targets = new ArrayList<>();
        // A fluid dragged out of JEI is a fluid, not the bucket holding it, so it goes in as an amount.
        Fluid fluid = JeiFluids.of(ingredient.getIngredient());
        if (fluid != null) {
            return screen.acceptsFluids() ? fluidTargets(screen, fluid) : targets;
        }
        Optional<ItemStack> maybeStack = ingredient.getItemStack();
        if (maybeStack.isEmpty()) {
            return targets;
        }
        ItemStack stack = maybeStack.get();
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            int index = i;
            Rect2i area = screen.inputSlotArea(i);
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I ignored) {
                    screen.setGhostInput(index, stack);
                }
            });
        }
        for (int i = 0; i < screen.outputSlotCount(); i++) {
            int index = i;
            Rect2i area = screen.outputSlotArea(i);
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I ignored) {
                    screen.setGhostOutput(index, stack);
                }
            });
        }
        return targets;
    }

    /** The same slot targets as an item drag, but writing the fluid into the slot instead of a stack. */
    private static <I> List<Target<I>> fluidTargets(RecipeEditorScreen screen, Fluid fluid) {
        List<Target<I>> targets = new ArrayList<>();
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            int index = i;
            Rect2i area = screen.inputSlotArea(i);
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I ignored) {
                    screen.setGhostInputFluid(index, fluid);
                }
            });
        }
        for (int i = 0; i < screen.outputSlotCount(); i++) {
            int index = i;
            Rect2i area = screen.outputSlotArea(i);
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I ignored) {
                    screen.setGhostOutputFluid(index, fluid);
                }
            });
        }
        return targets;
    }

    @Override
    public void onComplete() {
    }
}
