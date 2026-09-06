package org.mateof24.sce.core.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A crafting recipe that treats the data on its items as part of the recipe, on both sides of the arrow.
 *
 * <p>An ordinary recipe is blind to data. It matches a chest whatever is written on it or packed inside
 * it, and it produces a result that is always factory-fresh. That leaves two ordinary things impossible
 * to author: a recipe that only works with <em>this particular</em> item, and a recipe whose result comes
 * out already carrying something — an upgrade that keeps a container's contents, a quest reward that is a
 * named book, a tier change that preserves enchantments.
 *
 * <p>So the recipe carries two extra things, both captured from what the author physically put in the
 * editor's slots: the stacks the grid must contain, matched <em>with</em> their data, and the result as
 * the author left it. {@link Carry} then says where the crafted result's data comes from. Between the
 * two, every combination that means anything can be expressed, and no slot has to be singled out.
 *
 * <p><b>It is still an ordinary crafting recipe.</b> Its type is {@code minecraft:crafting}, inherited
 * from {@link CraftingRecipe}, so a crafting table finds it and JEI, EMI and REI list it in the crafting
 * category with no work on their side. Only matching and assembling differ.
 *
 * <p>Stacks are held as the text of their own save tag and read back the first time they are needed,
 * because a stack's components can only be read with the registries in hand, and those arrive with the
 * crafting call rather than with the file.
 */
public final class InheritingCraftingRecipe implements CraftingRecipe {
    /** Our own block in the recipe file, beside the vanilla fields rather than inside them. */
    public static final String DATA_KEY = "sce:data";
    /** Written as the recipe's {@code type}; must match what {@code SceRecipeSerializers} registers. */
    public static final String SHAPED_TYPE = "sce:crafting_shaped_inherit";
    public static final String SHAPELESS_TYPE = "sce:crafting_shapeless_inherit";

    /** Where the data on the crafted result comes from. */
    public enum Carry {
        /** A plain result, whatever the ingredients were carrying. */
        NONE,
        /** Only what the author put on the result in the editor. */
        RECIPE,
        /** Only what the ingredients used to craft it were carrying. */
        INGREDIENTS,
        /**
         * Both, with the recipe's own data winning where the two disagree — what the author wrote down
         * is a decision, and what an ingredient happens to carry should not quietly overrule it.
         */
        BOTH;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Carry of(String key) {
            for (Carry value : values()) {
                if (value.key().equals(key)) {
                    return value;
                }
            }
            return NONE;
        }
    }

    private final CraftingRecipe base;
    private final Serializer serializer;
    private final Carry carry;
    private final List<String> requiredText;
    private final String resultText;

    private List<ItemStack> required;
    private ItemStack resultStack;

    private InheritingCraftingRecipe(CraftingRecipe base, Carry carry, List<String> requiredText,
                                     String resultText, Serializer serializer) {
        this.base = base;
        this.carry = carry;
        this.requiredText = List.copyOf(requiredText);
        this.resultText = resultText;
        this.serializer = serializer;
    }

    // ------------------------------------------------------------------ the stacks, read once

    private List<ItemStack> required(HolderLookup.Provider registries) {
        if (required == null) {
            List<ItemStack> read = new ArrayList<>(requiredText.size());
            for (String text : requiredText) {
                ItemStack stack = readStack(registries, text);
                if (!stack.isEmpty()) {
                    read.add(stack);
                }
            }
            required = List.copyOf(read);
        }
        return required;
    }

    private ItemStack resultStack(HolderLookup.Provider registries) {
        if (resultStack == null) {
            resultStack = readStack(registries, resultText);
        }
        return resultStack;
    }

    // ------------------------------------------------------------------ matching

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return base.matches(input, level) && hasRequiredData(input, level.registryAccess());
    }

    /**
     * Whether the grid holds every stack this recipe insists on, data and all.
     *
     * <p>Each requirement has to be met by a <em>different</em> slot, so a recipe asking for two named
     * chests is not satisfied by one. Position is not checked: the vanilla recipe underneath has already
     * decided the layout is right, and a shapeless recipe has no layout at all.
     */
    private boolean hasRequiredData(CraftingInput input, HolderLookup.Provider registries) {
        List<ItemStack> wanted = required(registries);
        if (wanted.isEmpty()) {
            return true;
        }
        boolean[] taken = new boolean[input.size()];
        for (ItemStack want : wanted) {
            int found = -1;
            for (int slot = 0; slot < input.size() && found < 0; slot++) {
                if (!taken[slot] && ItemStack.isSameItemSameComponents(input.getItem(slot), want)) {
                    found = slot;
                }
            }
            if (found < 0) {
                return false;
            }
            taken[found] = true;
        }
        return true;
    }

    // ------------------------------------------------------------------ assembling

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = base.assemble(input, registries);
        switch (carry) {
            case RECIPE:
                applyRecipeData(result, registries);
                break;
            case INGREDIENTS:
                applyGridData(result, input);
                break;
            case BOTH:
                // The author's own decision goes on last, so it wins wherever the two describe the same
                // thing: what they wrote down is deliberate, what an ingredient happens to carry is not.
                applyGridData(result, input);
                applyRecipeData(result, registries);
                break;
            default:
                break;
        }
        return result;
    }

    private void applyRecipeData(ItemStack result, HolderLookup.Provider registries) {
        ItemStack defined = resultStack(registries);
        if (!defined.isEmpty()) {
            result.applyComponents(defined.getComponentsPatch());
        }
    }

    /**
     * Everything the ingredients were carrying, applied in grid order.
     *
     * <p>Every ingredient contributes, rather than one the author had to single out: a recipe can take
     * data from several items at once, and the ones carrying nothing add nothing. Where two describe the
     * same thing the later slot wins, which is at least a rule someone can predict.
     */
    private void applyGridData(ItemStack result, CraftingInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (!stack.isEmpty() && !stack.getComponentsPatch().isEmpty()) {
                result.applyComponents(stack.getComponentsPatch());
            }
        }
    }

    // ------------------------------------------------------------------ everything else is the base's

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return base.getRemainingItems(input);
    }

    @Override
    public String group() {
        return base.group();
    }

    @Override
    public CraftingBookCategory category() {
        return base.category();
    }

    @Override
    public PlacementInfo placementInfo() {
        return base.placementInfo();
    }

    /**
     * How a viewer and the recipe book draw this recipe.
     *
     * <p>The wrapped recipe already knows its own shape and ingredients, so those are taken as they come.
     * What is replaced is the result: a recipe that bakes data into what it makes has to <em>show</em>
     * that, or the player is promised a plain chest and handed one called "Pepito". This is the same
     * thing the older versions did by overriding {@code getResultItem}; from 1.21.11 the outcome a viewer
     * reads is a slot display, so the display is what gets swapped.
     *
     * <p>Only the two crafting shapes are rebuilt, because those are the only ones this recipe wraps.
     * Anything else is passed through untouched rather than guessed at.
     */
    @Override
    public List<RecipeDisplay> display() {
        List<RecipeDisplay> displays = base.display();
        if (carry == Carry.NONE) {
            return displays;
        }
        ItemStack defined = resultStack(null);
        if (defined.isEmpty()) {
            return displays;
        }
        SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(defined);
        List<RecipeDisplay> shown = new ArrayList<>(displays.size());
        for (RecipeDisplay display : displays) {
            if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                shown.add(new ShapedCraftingRecipeDisplay(shaped.width(), shaped.height(),
                        shaped.ingredients(), result, shaped.craftingStation()));
            } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
                shown.add(new ShapelessCraftingRecipeDisplay(shapeless.ingredients(), result,
                        shapeless.craftingStation()));
            } else {
                shown.add(display);
            }
        }
        return shown;
    }

    @Override
    public RecipeSerializer<? extends CraftingRecipe> getSerializer() {
        return serializer;
    }

    // ------------------------------------------------------------------ reading and writing

    /** A stack written as the text of its own save tag, which keeps every kind of data in one field. */
    public static ItemStack readStack(HolderLookup.Provider registries, String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            Tag tag = TagParser.parseCompoundFully(snbt);
            return ItemStack.CODEC.parse(ops(registries), tag).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    public static String writeStack(HolderLookup.Provider registries, ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        return ItemStack.CODEC.encodeStart(ops(registries), stack)
                .result().map(Tag::toString).orElse("");
    }

    /**
     * The ops a stack is read and written with. Registries are needed because a component can name one;
     * when there are none to hand — drawing a preview before the world is up — plain NBT ops still read
     * everything that does not.
     */
    private static DynamicOps<Tag> ops(HolderLookup.Provider registries) {
        return registries == null ? NbtOps.INSTANCE : registries.createSerializationContext(NbtOps.INSTANCE);
    }

    /** Builds the block the editor writes, so the shape of the file lives in one place. */
    public static JsonObject writeData(String carry, List<String> required, String resultStack) {
        JsonObject data = new JsonObject();
        data.addProperty("carry", Carry.of(carry).key());
        if (!required.isEmpty()) {
            JsonArray array = new JsonArray();
            required.forEach(array::add);
            data.add("require", array);
        }
        if (resultStack != null && !resultStack.isEmpty()) {
            data.addProperty("result", resultStack);
        }
        return data;
    }

    /** The recipe's own block, as the codec sees it: the setting and the stacks, in text form. */
    private record Data(String carry, List<String> require, String result) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("carry", "none").forGetter(Data::carry),
                Codec.STRING.listOf().optionalFieldOf("require", List.of()).forGetter(Data::require),
                Codec.STRING.optionalFieldOf("result", "").forGetter(Data::result)
        ).apply(instance, Data::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::carry,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Data::require,
                ByteBufCodecs.STRING_UTF8, Data::result,
                Data::new);
    }

    /**
     * Reads and writes the vanilla recipe it wraps, plus one block of our own beside it.
     *
     * <p>The vanilla codec does all the real work, which is the point: the file stays a normal shaped or
     * shapeless recipe that someone could still read by hand, with the data parts gathered under a single
     * key rather than scattered through fields the game would not expect them in.
     */
    public static final class Serializer implements RecipeSerializer<InheritingCraftingRecipe> {
        private final MapCodec<InheritingCraftingRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, InheritingCraftingRecipe> streamCodec;

        public <T extends CraftingRecipe> Serializer(RecipeSerializer<T> vanilla) {
            // The wrapped recipe is always the shape this serializer was built for. The pairing is fixed
            // here and cannot be got wrong from outside, which is what the cast rests on.
            @SuppressWarnings("unchecked")
            java.util.function.Function<InheritingCraftingRecipe, T> unwrap = recipe -> (T) recipe.base;
            this.codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    vanilla.codec().forGetter(unwrap),
                    Data.CODEC.optionalFieldOf(DATA_KEY, new Data("none", List.of(), ""))
                            .forGetter(recipe -> new Data(recipe.carry.key(), recipe.requiredText, recipe.resultText))
            ).apply(instance, (base, data) -> new InheritingCraftingRecipe(
                    base, Carry.of(data.carry()), data.require(), data.result(), this)));
            this.streamCodec = StreamCodec.composite(
                    vanilla.streamCodec(), unwrap,
                    Data.STREAM_CODEC,
                    recipe -> new Data(recipe.carry.key(), recipe.requiredText, recipe.resultText),
                    (base, data) -> new InheritingCraftingRecipe(
                            base, Carry.of(data.carry()), data.require(), data.result(), this));
        }

        @Override
        public MapCodec<InheritingCraftingRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, InheritingCraftingRecipe> streamCodec() {
            return streamCodec;
        }
    }
}
