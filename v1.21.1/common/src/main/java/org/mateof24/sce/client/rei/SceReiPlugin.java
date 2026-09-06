package org.mateof24.sce.client.rei;

import dev.architectury.fluid.FluidStack;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.overlay.OverlayListWidget;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.mateof24.sce.client.SceClient;

import java.util.Optional;

/**
 * REI integration. Discovered through the {@code rei_client} entrypoint on Fabric and through a subclass
 * annotated {@code @REIPluginClient} on Forge and NeoForge — unlike JEI's and EMI's, REI's annotation
 * lives in the loader-specific half of its jar, so it cannot be written here.
 *
 * <p>Like the other two viewers, REI reacts to added and disabled recipes on its own: those reach the
 * client as an ordinary recipe update. What is added here is dragging an entry from REI's list into the
 * editor's slots, and answering what the pointer is over so the open key works over REI's list too.
 */
@Environment(EnvType.CLIENT)
public class SceReiPlugin implements REIClientPlugin {
    private static boolean providerRegistered;

    @Override
    public void registerScreens(ScreenRegistry registry) {
        registry.registerDraggableStackVisitor(new RecipeEditorReiDragHandler());
        // Registered once even if the plugin is built again on a reload: the providers are a list, and a
        // second copy would answer the same question twice.
        if (!providerRegistered) {
            providerRegistered = true;
            SceClient.registerHoveredItemProvider(SceReiPlugin::hoveredItem);
        }
    }

    /** What the pointer is over in REI's own list, or in its favourites when it is over those instead. */
    private static ItemStack hoveredItem() {
        Optional<ScreenOverlay> overlay = REIRuntime.getInstance().getOverlay();
        if (overlay.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack listed = itemOf(overlay.get().getEntryList().getFocusedStack());
        if (!listed.isEmpty()) {
            return listed;
        }
        Optional<OverlayListWidget> favourites = overlay.get().getFavoritesList();
        return favourites.map(list -> itemOf(list.getFocusedStack())).orElse(ItemStack.EMPTY);
    }

    /** The item an entry holds, or an empty stack when the entry is not an item at all. */
    static ItemStack itemOf(EntryStack<?> entry) {
        if (entry == null || entry.isEmpty() || entry.getType() != VanillaEntryTypes.ITEM) {
            return ItemStack.EMPTY;
        }
        return entry.getValue() instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    /**
     * The fluid an entry holds, or null when the entry is not a fluid.
     *
     * <p>REI is built on Architectury, so its fluids arrive as {@link FluidStack} — the same type this mod
     * already deals in, which is why this needs none of the reflection JEI's fluids do.
     */
    static Fluid fluidOf(EntryStack<?> entry) {
        if (entry == null || entry.isEmpty() || entry.getType() != VanillaEntryTypes.FLUID) {
            return null;
        }
        return entry.getValue() instanceof FluidStack fluid && !fluid.isEmpty() ? fluid.getFluid() : null;
    }
}
