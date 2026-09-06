package org.mateof24.sce.client.rei;

import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lets entries be dragged straight from REI's list onto the editor's recipe slots.
 *
 * <p>The same behaviour as the JEI and EMI handlers, expressed the way REI asks for it: one method says
 * where a drag may be dropped, which is what REI highlights, and another takes the drop. A fluid offers
 * no target at all on a recipe type that cannot hold one, so the highlight never promises something the
 * drop would refuse.
 */
@Environment(EnvType.CLIENT)
public class RecipeEditorReiDragHandler implements DraggableStackVisitor<RecipeEditorScreen> {
    @Override
    public <R extends Screen> boolean isHandingScreen(R screen) {
        return screen instanceof RecipeEditorScreen;
    }

    @Override
    public DraggedAcceptorResult acceptDraggedStack(DraggingContext<RecipeEditorScreen> context, DraggableStack dragged) {
        RecipeEditorScreen screen = context.getScreen();
        int mouseX = mouseX();
        int mouseY = mouseY();

        // A fluid dragged out of REI is a fluid, not the bucket holding it, so it goes in as an amount.
        Fluid fluid = SceReiPlugin.fluidOf(dragged.getStack());
        if (fluid != null) {
            for (int i = 0; i < screen.inputSlotCount(); i++) {
                if (contains(screen.inputSlotArea(i), mouseX, mouseY)) {
                    return result(screen.setGhostInputFluid(i, fluid));
                }
            }
            for (int i = 0; i < screen.outputSlotCount(); i++) {
                if (contains(screen.outputSlotArea(i), mouseX, mouseY)) {
                    return result(screen.setGhostOutputFluid(i, fluid));
                }
            }
            return DraggedAcceptorResult.PASS;
        }

        ItemStack stack = SceReiPlugin.itemOf(dragged.getStack());
        if (stack.isEmpty()) {
            return DraggedAcceptorResult.PASS;
        }
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            if (contains(screen.inputSlotArea(i), mouseX, mouseY)) {
                screen.setGhostInput(i, stack);
                return DraggedAcceptorResult.ACCEPTED;
            }
        }
        for (int i = 0; i < screen.outputSlotCount(); i++) {
            if (contains(screen.outputSlotArea(i), mouseX, mouseY)) {
                screen.setGhostOutput(i, stack);
                return DraggedAcceptorResult.ACCEPTED;
            }
        }
        return DraggedAcceptorResult.PASS;
    }

    @Override
    public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<RecipeEditorScreen> context,
                                                              DraggableStack dragged) {
        RecipeEditorScreen screen = context.getScreen();
        boolean fluid = SceReiPlugin.fluidOf(dragged.getStack()) != null;
        if (fluid ? !screen.acceptsFluids() : SceReiPlugin.itemOf(dragged.getStack()).isEmpty()) {
            return Stream.empty(); // nothing here can hold it, so do not offer a target
        }
        List<BoundsProvider> areas = new ArrayList<>();
        for (int i = 0; i < screen.inputSlotCount(); i++) {
            areas.add(boundsOf(screen.inputSlotArea(i)));
        }
        for (int i = 0; i < screen.outputSlotCount(); i++) {
            areas.add(boundsOf(screen.outputSlotArea(i)));
        }
        return areas.stream();
    }

    /**
     * A slot as the shape REI wants its accepting areas in.
     *
     * <p>REI's own helper takes its rectangle type, which lives in a library its jar does not carry; the
     * interface is a single method returning a shape, so the shape is built here instead and this module
     * keeps compiling against nothing but REI's own jar.
     */
    private static BoundsProvider boundsOf(Rect2i area) {
        VoxelShape shape = Shapes.box(area.getX(), area.getY(), 0.0,
                area.getX() + area.getWidth(), area.getY() + area.getHeight(), 1.0);
        return () -> shape;
    }

    private static DraggedAcceptorResult result(boolean accepted) {
        return accepted ? DraggedAcceptorResult.ACCEPTED : DraggedAcceptorResult.PASS;
    }

    private static boolean contains(Rect2i area, int mouseX, int mouseY) {
        return mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    /**
     * Where the pointer is, in the coordinates the screen is laid out in.
     *
     * <p>Worked out the same way the game works it out before handing a click to a screen. REI can say
     * this itself, but only as its own point type, which is in the library its jar does not carry.
     */
    private static int mouseX() {
        Minecraft minecraft = Minecraft.getInstance();
        return (int) (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth());
    }

    private static int mouseY() {
        Minecraft minecraft = Minecraft.getInstance();
        return (int) (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight());
    }
}
