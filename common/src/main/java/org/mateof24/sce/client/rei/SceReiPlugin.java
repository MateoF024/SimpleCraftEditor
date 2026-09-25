package org.mateof24.sce.client.rei;

import dev.architectury.fluid.FluidStack;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.overlay.OverlayListWidget;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import dev.architectury.event.EventResult;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.plugin.common.displays.anvil.AnvilRecipe;
import me.shedaniel.rei.plugin.common.displays.anvil.DefaultAnvilDisplay;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.AnvilRuleDisplay;
import org.mateof24.sce.client.SceClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Puts the anvil rules into REI's own anvil category, and hides what they replace.
     *
     * <p>REI does ask the game whether one item repairs another - it is the only one of the three that
     * does - but only for some of them, so the rules are stated here as well and the two agree for all
     * of them.
     *
     * <p>The displays are built when REI builds its list, on a resource reload. The visibility rule is
     * not: REI asks it every time it draws, so a rule that takes the game's own material away takes it
     * away at once.
     */
    @Override
    public void registerDisplays(DisplayRegistry registry) {
        int n = 0;
        for (AnvilRuleDisplay.Entry entry : AnvilRuleDisplay.entries()) {
            for (ItemStack target : entry.targets()) {
                if (!AnvilRuleDisplay.repairable(target)) {
                    continue;
                }
                registry.add(new DefaultAnvilDisplay(new AnvilRecipe(
                        new ResourceLocation(SimpleCraftEditor.MOD_ID, "anvil_rule_" + n++),
                        List.of(AnvilRuleDisplay.worn(target)),
                        entry.materials(),
                        List.of(AnvilRuleDisplay.mended(target)))));
            }
        }
        registry.registerVisibilityPredicate((category, display) -> {
            if (!(display instanceof DefaultAnvilDisplay anvil)) {
                return EventResult.pass();
            }
            Set<Item> replaced = AnvilRuleDisplay.replacedTargets();
            return !replaced.isEmpty() && isMaterialRepairOf(anvil, replaced)
                    ? EventResult.interruptFalse() : EventResult.pass();
        });
    }

    /**
     * Whether this is the game's own repair of one of these items <em>with a material</em>, which is the
     * only thing a rule replaces.
     *
     * <p>The anvil category holds three entries that all show the same item going in and coming out, and
     * a rule speaks about one of them. Mending a sword with an ingot is what "instead of" takes away.
     * Mending a sword with a second sword is the anvil joining two of a kind, and putting an enchantment
     * on it from a book is a third thing: neither ever reaches the hook, so the anvil goes on allowing
     * both and the viewer must go on showing them. What separates the three is the right-hand side.
     */
    private static boolean isMaterialRepairOf(DefaultAnvilDisplay anvil, Set<Item> items) {
        List<EntryIngredient> inputs = anvil.getInputEntries();
        if (inputs.size() < 2 || anvil.getDisplayLocation().map(id ->
                id.getNamespace().equals(SimpleCraftEditor.MOD_ID)).orElse(false)) {
            return false;
        }
        Set<Item> going = itemsOf(inputs.get(0));
        boolean repairOfReplaced = going.stream().anyMatch(items::contains)
                && anvil.getOutputEntries().stream().anyMatch(entry ->
                        itemsOf(entry).stream().anyMatch(items::contains));
        if (!repairOfReplaced) {
            return false;
        }
        Set<Item> with = itemsOf(inputs.get(1));
        return !with.isEmpty() && with.stream().noneMatch(item ->
                going.contains(item) || item == Items.BOOK || item == Items.ENCHANTED_BOOK);
    }

    /** The items a REI entry can stand for; anything that is not an item is not one of them. */
    private static Set<Item> itemsOf(EntryIngredient entry) {
        Set<Item> items = new LinkedHashSet<>();
        for (EntryStack<?> stack : entry) {
            if (stack.getValue() instanceof ItemStack item && !item.isEmpty()) {
                items.add(item.getItem());
            }
        }
        return items;
    }

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
