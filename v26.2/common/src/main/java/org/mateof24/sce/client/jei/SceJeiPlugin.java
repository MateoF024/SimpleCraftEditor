package org.mateof24.sce.client.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.AnvilRuleDisplay;
import org.mateof24.sce.core.anvil.AnvilRule;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JEI integration. Discovered via {@code @JeiPlugin} on Forge and the {@code jei_mod_plugin} entrypoint
 * on Fabric. JEI already reacts to added/disabled recipes because those are re-synced to the client as a
 * normal recipe update, which JEI listens for; this plugin adds the drag-from-JEI support in the editor.
 */
@JeiPlugin
@Environment(EnvType.CLIENT)
public class SceJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    private static boolean providerRegistered;
    /** The game's own repair recipes a rule has replaced, so turning the rule off puts them back. */
    private static final List<IJeiAnvilRecipe> hiddenVanilla = new ArrayList<>();

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipeEditorScreen.class, new RecipeEditorGhostHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        if (!providerRegistered) {
            providerRegistered = true;
            SceClient.registerHoveredItemProvider(SceJeiPlugin::hoveredItem);
            // JEI is the one viewer that can be told mid-game; the other two read the rules when they
            // build their lists, which is on a resource reload.
            SceClient.onAnvilRulesChanged(SceJeiPlugin::applyAnvilRules);
        }
        applyAnvilRules();
    }

    /**
     * Hands JEI the rules to ask about, rather than handing it the recipes to keep.
     *
     * <p>JEI never asks the game whether one item repairs another: it walks the tier and armour-material
     * tables itself, so the hook that makes the anvil obey is invisible here and the rules have to be
     * stated separately for the two to agree.
     */
    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(RecipeTypes.ANVIL,
                new AnvilRulePlugin(registration.getJeiHelpers().getVanillaRecipeFactory()));
    }

    /**
     * Takes out the repairs the rules replaced, and puts back the ones no rule replaces any more.
     *
     * <p>Showing what a rule <em>allows</em> belongs to {@link AnvilRulePlugin}, which is asked live.
     * Hiding is the other half and has to happen here, because what is being hidden is JEI's own.
     * Called when the runtime arrives and again on every rule change, so a rule written mid-game takes
     * effect without a reload.
     */
    public static void applyAnvilRules() {
        if (runtime == null) {
            return;
        }
        IRecipeManager manager = runtime.getRecipeManager();
        if (!hiddenVanilla.isEmpty()) {
            manager.unhideRecipes(RecipeTypes.ANVIL, List.copyOf(hiddenVanilla));
            hiddenVanilla.clear();
        }
        Set<Item> replaced = AnvilRuleDisplay.replacedTargets();
        if (replaced.isEmpty()) {
            return;
        }
        manager.createRecipeLookup(RecipeTypes.ANVIL).includeHidden().get()
                .filter(recipe -> isMaterialRepairOf(recipe, replaced))
                .forEach(hiddenVanilla::add);
        if (!hiddenVanilla.isEmpty()) {
            manager.hideRecipes(RecipeTypes.ANVIL, List.copyOf(hiddenVanilla));
        }
    }

    /**
     * Whether this is the game's own repair of one of these items <em>with a material</em>, which is the
     * only thing a rule replaces.
     *
     * <p>JEI's Anvil category holds three entries that all show the same item on the left and in the
     * output, and a rule speaks about one of them. Mending a sword with an ingot is what "instead of"
     * takes away. Mending a sword with a second sword is the anvil joining two of a kind, and putting an
     * enchantment on it from a book is a third thing: neither ever reaches the hook, so the anvil goes on
     * allowing both and the viewer must go on showing them. What separates the three is the right-hand
     * side, so that is what this reads - a fact about the recipe rather than about how JEI named it.
     */
    private static boolean isMaterialRepairOf(IJeiAnvilRecipe recipe, Set<Item> items) {
        List<ItemStack> rights = recipe.getRightInputs();
        if (rights.isEmpty() || ours(recipe)) {
            return false;
        }
        Set<Item> lefts = new LinkedHashSet<>();
        for (ItemStack stack : recipe.getLeftInputs()) {
            lefts.add(stack.getItem());
        }
        boolean repairOfReplaced = lefts.stream().anyMatch(items::contains)
                && recipe.getOutputs().stream().anyMatch(stack -> items.contains(stack.getItem()));
        return repairOfReplaced && rights.stream().noneMatch(stack -> lefts.contains(stack.getItem())
                || stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK));
    }

    /** Whether this recipe is one of the rules, which the same search hands back along with the rest. */
    private static boolean ours(IJeiAnvilRecipe recipe) {
        Identifier uid = recipe.getUid();
        return uid != null && SimpleCraftEditor.MOD_ID.equals(uid.getNamespace());
    }

    /**
     * The anvil rules, answered as JEI asks for them instead of being added to its index.
     *
     * <p>JEI can be given recipes and can be told to hide them, but it cannot be told to forget them.
     * Adding them meant that deleting a rule left its recipe behind - hidden if it was still ours to
     * hide, and duplicated on the next edit for every rule that survived. Answering the questions leaves
     * nothing to forget: the list is what the rules say at the moment JEI asks, and a rule that is gone
     * is simply not in the answer.
     *
     * <p>The list is rebuilt when the rules change and reused in between, because JEI asks often. A fresh
     * one of these is made every time JEI reloads, which is also when a tag could have gained a member.
     */
    private static final class AnvilRulePlugin implements ISimpleRecipeManagerPlugin<IJeiAnvilRecipe> {
        private final IVanillaRecipeFactory factory;
        private List<AnvilRule> builtFrom;
        private List<IJeiAnvilRecipe> recipes = List.of();

        AnvilRulePlugin(IVanillaRecipeFactory factory) {
            this.factory = factory;
        }

        @Override
        public List<IJeiAnvilRecipe> getAllRecipes() {
            List<AnvilRule> rules = AnvilRules.INSTANCE.rules();
            if (rules != builtFrom) {
                builtFrom = rules;
                recipes = build();
            }
            return recipes;
        }

        @Override
        public boolean isHandledInput(ITypedIngredient<?> ingredient) {
            return !getRecipesForInput(ingredient).isEmpty();
        }

        @Override
        public boolean isHandledOutput(ITypedIngredient<?> ingredient) {
            return !getRecipesForOutput(ingredient).isEmpty();
        }

        @Override
        public List<IJeiAnvilRecipe> getRecipesForInput(ITypedIngredient<?> ingredient) {
            return matching(ingredient, true);
        }

        @Override
        public List<IJeiAnvilRecipe> getRecipesForOutput(ITypedIngredient<?> ingredient) {
            return matching(ingredient, false);
        }

        /**
         * The rules that mention this item, matched on the item alone. What the player clicked is a whole
         * item and what a rule draws is a worn one; refusing to answer over that difference would be
         * refusing to answer at all.
         */
        private List<IJeiAnvilRecipe> matching(ITypedIngredient<?> ingredient, boolean asInput) {
            Optional<ItemStack> clicked = ingredient.getItemStack();
            if (clicked.isEmpty()) {
                return List.of();
            }
            Item item = clicked.get().getItem();
            List<IJeiAnvilRecipe> found = new ArrayList<>();
            for (IJeiAnvilRecipe recipe : getAllRecipes()) {
                boolean hit = asInput
                        ? has(recipe.getLeftInputs(), item) || has(recipe.getRightInputs(), item)
                        : has(recipe.getOutputs(), item);
                if (hit) {
                    found.add(recipe);
                }
            }
            return found;
        }

        private static boolean has(List<ItemStack> stacks, Item item) {
            return stacks.stream().anyMatch(stack -> stack.getItem() == item);
        }

        private List<IJeiAnvilRecipe> build() {
            List<IJeiAnvilRecipe> built = new ArrayList<>();
            int n = 0;
            for (AnvilRuleDisplay.Entry entry : AnvilRuleDisplay.entries()) {
                for (ItemStack target : entry.targets()) {
                    if (!AnvilRuleDisplay.repairable(target)) {
                        continue;
                    }
                    built.add(factory.createAnvilRecipe(
                            List.of(AnvilRuleDisplay.worn(target)),
                            entry.materials(),
                            List.of(AnvilRuleDisplay.mended(target)),
                            Identifier.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, "anvil_rule_" + n++)));
                }
            }
            return built;
        }
    }

    private static ItemStack hoveredItem() {
        if (runtime == null) {
            return ItemStack.EMPTY;
        }
        ItemStack listed = runtime.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (listed != null && !listed.isEmpty()) {
            return listed;
        }
        ItemStack bookmarked = runtime.getBookmarkOverlay().getItemStackUnderMouse();
        return bookmarked != null ? bookmarked : ItemStack.EMPTY;
    }
}
