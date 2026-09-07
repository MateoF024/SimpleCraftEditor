package org.mateof24.sce.registry;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.recipe.InheritingCraftingRecipe;

/**
 * Registers the two recipe kinds this mod adds of its own: a shaped and a shapeless crafting recipe
 * that pass one ingredient's data on to the result. See {@link InheritingCraftingRecipe} for why.
 *
 * <p>Nothing is orphaned by this. A recipe of one of these kinds only ever exists because this mod put
 * it there, and it lives in this mod's own file rather than in the pack's datapacks — remove the mod and
 * the recipe simply stops being added, exactly like every other recipe authored here.
 */
public final class SceRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(SimpleCraftEditor.MOD_ID, Registries.RECIPE_SERIALIZER);

    // Built here rather than inside the suppliers so a recipe can name its own serializer without going
    // back through the registry, which is not safe to read at every moment a recipe might be built.
    // The vanilla serializers each live on their own recipe class from 26.1.2, rather than together as
    // constants on RecipeSerializer.
    public static final InheritingCraftingRecipe.Serializer SHAPED_INHERIT =
            new InheritingCraftingRecipe.Serializer(ShapedRecipe.SERIALIZER);
    public static final InheritingCraftingRecipe.Serializer SHAPELESS_INHERIT =
            new InheritingCraftingRecipe.Serializer(ShapelessRecipe.SERIALIZER);

    /** {@code sce:crafting_shaped_inherit} — a shaped recipe whose result inherits an ingredient's data. */
    public static final RegistrySupplier<RecipeSerializer<?>> SHAPED =
            SERIALIZERS.register("crafting_shaped_inherit", SHAPED_INHERIT::get);

    /** {@code sce:crafting_shapeless_inherit} — the same, without a pattern. */
    public static final RegistrySupplier<RecipeSerializer<?>> SHAPELESS =
            SERIALIZERS.register("crafting_shapeless_inherit", SHAPELESS_INHERIT::get);

    /** The recipe types these serializers answer to, as they are written in a recipe file. */
    public static final String SHAPED_TYPE = SimpleCraftEditor.MOD_ID + ":crafting_shaped_inherit";
    public static final String SHAPELESS_TYPE = SimpleCraftEditor.MOD_ID + ":crafting_shapeless_inherit";

    private SceRecipeSerializers() {
    }

    public static void init() {
        SERIALIZERS.register();
    }
}
