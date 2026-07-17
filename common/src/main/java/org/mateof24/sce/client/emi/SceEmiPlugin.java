package org.mateof24.sce.client.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.List;

/**
 * EMI integration. Discovered via {@code @EmiEntrypoint} on Forge and the {@code emi} entrypoint on Fabric.
 * EMI reacts to added/disabled recipes on its own: those are re-synced to the client as a normal recipe
 * update, which EMI reloads on. This plugin adds drag-from-EMI support in the editor. When EMI and JEI are
 * both present EMI is the active viewer (it runs JEI beneath it), and both drag handlers are registered so
 * whichever list is shown works.
 */
@EmiEntrypoint
@Environment(EnvType.CLIENT)
public class SceEmiPlugin implements EmiPlugin {
    private static boolean providerRegistered;

    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(RecipeEditorScreen.class, new RecipeEditorEmiDragHandler());
        if (!providerRegistered) {
            providerRegistered = true;
            SceClient.registerHoveredItemProvider(SceEmiPlugin::hoveredItem);
        }
    }

    private static ItemStack hoveredItem() {
        EmiStackInteraction hovered = EmiApi.getHoveredStack(false);
        if (hovered == null || hovered.isEmpty()) {
            return ItemStack.EMPTY;
        }
        List<EmiStack> stacks = hovered.getStack().getEmiStacks();
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).getItemStack();
    }
}
