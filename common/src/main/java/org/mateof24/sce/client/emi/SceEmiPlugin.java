package org.mateof24.sce.client.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.AnvilRuleDisplay;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        addAnvilRules(registry);
        if (!providerRegistered) {
            providerRegistered = true;
            SceClient.registerHoveredItemProvider(SceEmiPlugin::hoveredItem);
        }
    }

    /**
     * Puts the anvil rules into EMI's own repairing category, and takes out what they replace.
     *
     * <p>EMI never asks the game whether one item repairs another: it reads the tier and armour-material
     * tables itself, so the hook that makes the anvil obey is invisible here.
     *
     * <p>This runs when EMI builds its list, which is on a resource reload, and EMI's public API offers
     * no way in afterwards. A rule written while the game is running therefore reaches EMI on the next
     * reload rather than at once - which the editor says when it saves.
     */
    private static void addAnvilRules(EmiRegistry registry) {
        Set<Item> replaced = AnvilRuleDisplay.replacedTargets();
        if (!replaced.isEmpty()) {
            registry.removeRecipes(recipe -> isMaterialRepairOf(recipe, replaced));
        }
        int n = 0;
        for (AnvilRuleDisplay.Entry entry : AnvilRuleDisplay.entries()) {
            for (ItemStack target : entry.targets()) {
                if (!AnvilRuleDisplay.repairable(target)) {
                    continue;
                }
                List<EmiStack> materials = new ArrayList<>();
                for (ItemStack material : entry.materials()) {
                    materials.add(EmiStack.of(material));
                }
                registry.addRecipe(new AnvilRuleRecipe(
                        new ResourceLocation(SimpleCraftEditor.MOD_ID, "anvil_rule_" + n++),
                        EmiStack.of(AnvilRuleDisplay.worn(target)),
                        EmiIngredient.of(materials),
                        EmiStack.of(AnvilRuleDisplay.mended(target))));
            }
        }
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
    private static boolean isMaterialRepairOf(EmiRecipe recipe, Set<Item> items) {
        List<EmiIngredient> inputs = recipe.getInputs();
        if (recipe.getCategory() != VanillaEmiRecipeCategories.ANVIL_REPAIRING || inputs.size() < 2
                || (recipe.getId() != null
                        && SimpleCraftEditor.MOD_ID.equals(recipe.getId().getNamespace()))) {
            return false;
        }
        Set<Item> going = itemsOf(inputs.get(0));
        boolean repairOfReplaced = going.stream().anyMatch(items::contains)
                && recipe.getOutputs().stream().anyMatch(out ->
                        items.contains(out.getItemStack().getItem()));
        if (!repairOfReplaced) {
            return false;
        }
        Set<Item> with = itemsOf(inputs.get(1));
        return !with.isEmpty() && with.stream().noneMatch(item ->
                going.contains(item) || item == Items.BOOK || item == Items.ENCHANTED_BOOK);
    }

    /** The items an EMI ingredient can stand for; anything that is not an item is not one of them. */
    private static Set<Item> itemsOf(EmiIngredient ingredient) {
        Set<Item> items = new LinkedHashSet<>();
        for (EmiStack stack : ingredient.getEmiStacks()) {
            ItemStack item = stack.getItemStack();
            if (!item.isEmpty()) {
                items.add(item.getItem());
            }
        }
        return items;
    }

    /**
     * One rule, drawn where EMI draws its own anvil repairs: the item, a plus, the material, an arrow,
     * the result. The numbers are EMI's own, so a rule sits in the category looking like everything else
     * in it rather than like a guest.
     */
    private static final class AnvilRuleRecipe extends BasicEmiRecipe {
        private final EmiStack worn;
        private final EmiIngredient material;
        private final EmiStack mended;

        AnvilRuleRecipe(ResourceLocation id, EmiStack worn, EmiIngredient material, EmiStack mended) {
            super(VanillaEmiRecipeCategories.ANVIL_REPAIRING, id, 125, 18);
            this.worn = worn;
            this.material = material;
            this.mended = mended;
            this.inputs = List.of(worn, material);
            this.outputs = List.of(mended);
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            widgets.addTexture(EmiTexture.PLUS, 27, 3);
            widgets.addTexture(EmiTexture.EMPTY_ARROW, 75, 1);
            widgets.addSlot(worn, 0, 0);
            widgets.addSlot(material, 49, 0);
            widgets.addSlot(mended, 107, 0).recipeContext(this);
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
