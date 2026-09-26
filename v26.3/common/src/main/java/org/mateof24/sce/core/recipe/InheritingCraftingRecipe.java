package org.mateof24.sce.core.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
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
import net.minecraft.world.item.ItemStackTemplate;
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
import java.util.Optional;
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
    /**
     * The stacks the grid must hold, data and all, as templates rather than as stacks.
     *
     * <p>A template is an item, a count and a component patch, and needs nothing bound to exist. A live
     * stack does: it copies the item's own component map, and on 26.3 the recipe set is built while the
     * reloadable registries are still loading, before any item has one. Building a stack there throws,
     * and the recipe is dropped from the load - silently, as far as the player can see, because every
     * screen in this mod reads the stored file rather than the game.
     */
    private final List<ItemStackTemplate> required;
    /** The same stacks as the game wants them, built the first time a grid is actually checked. */
    private List<ItemStack> requiredStacks;
    /** The result as the author left it, for {@link Carry#RECIPE} and {@link Carry#BOTH}. */
    private final Optional<ItemStackTemplate> resultStack;

    private InheritingCraftingRecipe(CraftingRecipe base, Carry carry,
                                     List<Optional<ItemStackTemplate>> required,
                                     Optional<ItemStackTemplate> resultStack, Serializer serializer) {
        this.base = base;
        this.carry = carry;
        this.required = required.stream().flatMap(Optional::stream).toList();
        this.resultStack = resultStack;
        this.serializer = serializer;
    }

    // ------------------------------------------------------------------ matching

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return base.matches(input, level) && hasRequiredData(input);
    }

    /**
     * Whether the grid holds every stack this recipe insists on, data and all.
     *
     * <p>Each requirement has to be met by a <em>different</em> slot, so a recipe asking for two named
     * chests is not satisfied by one. Position is not checked: the vanilla recipe underneath has already
     * decided the layout is right, and a shapeless recipe has no layout at all.
     */
    /**
     * The required stacks as the game wants to compare them.
     *
     * <p>Built here and not when the recipe was read, which is the whole point: by the time anything is
     * in a crafting grid the items have their components, and a template can become a stack.
     */
    private List<ItemStack> requiredStacks() {
        if (requiredStacks == null) {
            List<ItemStack> built = new ArrayList<>(required.size());
            for (ItemStackTemplate template : required) {
                built.add(template.create());
            }
            requiredStacks = built;
        }
        return requiredStacks;
    }

    private boolean hasRequiredData(CraftingInput input) {
        List<ItemStack> wanted = requiredStacks();
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
    public ItemStack assemble(CraftingInput input) {
        ItemStack result = base.assemble(input);
        switch (carry) {
            case RECIPE:
                applyRecipeData(result);
                break;
            case INGREDIENTS:
                applyGridData(result, input);
                break;
            case BOTH:
                // The author's own decision goes on last, so it wins wherever the two describe the same
                // thing: what they wrote down is deliberate, what an ingredient happens to carry is not.
                applyGridData(result, input);
                applyRecipeData(result);
                break;
            default:
                break;
        }
        return result;
    }

    private void applyRecipeData(ItemStack result) {
        // The patch, not a stack: it is the data the author pinned, and nothing here has to be built.
        resultStack.ifPresent(template -> result.applyComponents(template.components()));
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

    /** Whether the recipe book pops up for this recipe. The wrapped recipe's answer, like the rest. */
    @Override
    public boolean showNotification() {
        return base.showNotification();
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
        if (resultStack.isEmpty()) {
            return displays;
        }
        // A display carries a template rather than a stack from 26.1.2, which is what this already holds.
        SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(resultStack.get());
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
        return serializer.get();
    }

    // ------------------------------------------------------------------ reading and writing

    /**
     * A stack held as the text of its own save tag, read and written through whatever ops the recipe
     * itself is being read with.
     *
     * <p>That last part is the whole point. A stack's components can name a registry entry — an
     * enchantment, a potion — and resolving one needs the registries, which are in hand at exactly one
     * moment: while the recipe file is being read. They are not there afterwards. The game asks every
     * recipe for its display as it finishes loading them, and from 26.1.2 {@code assemble} is not handed
     * them either, so a stack read late would come back stripped of the very data this recipe exists to
     * carry — silently, because a stack that fails to read is simply empty.
     *
     * <p>The text form is kept because the file is the same file on every version this mod supports.
     */
    private static final Codec<Optional<ItemStackTemplate>> STACK_AS_SNBT = new Codec<>() {
        @Override
        public <T> DataResult<Pair<Optional<ItemStackTemplate>, T>> decode(DynamicOps<T> ops, T input) {
            return Codec.STRING.decode(ops, input).flatMap(read -> {
                String snbt = read.getFirst();
                if (snbt.isEmpty()) {
                    return DataResult.success(Pair.of(Optional.<ItemStackTemplate>empty(), read.getSecond()));
                }
                try {
                    T value = NbtOps.INSTANCE.convertTo(ops, TagParser.parseCompoundFully(snbt));
                    return ItemStackTemplate.CODEC.decode(ops, value)
                            .map(pair -> Pair.of(Optional.of(pair.getFirst()), read.getSecond()));
                } catch (Exception e) {
                    return DataResult.error(() -> "Not a readable item stack: " + snbt);
                }
            });
        }

        @Override
        public <T> DataResult<T> encode(Optional<ItemStackTemplate> template, DynamicOps<T> ops, T prefix) {
            if (template.isEmpty()) {
                return Codec.STRING.encode("", ops, prefix);
            }
            return ItemStackTemplate.CODEC.encodeStart(ops, template.get())
                    .flatMap(value -> Codec.STRING.encode(
                            ops.convertTo(NbtOps.INSTANCE, value).toString(), ops, prefix));
        }
    };

    public static String writeStack(HolderLookup.Provider registries, ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        return ItemStackTemplate.CODEC.encodeStart(ops(registries), ItemStackTemplate.fromNonEmptyStack(stack))
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
    private record Data(String carry, List<Optional<ItemStackTemplate>> require,
                        Optional<ItemStackTemplate> result) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("carry", "none").forGetter(Data::carry),
                STACK_AS_SNBT.listOf().optionalFieldOf("require", List.of()).forGetter(Data::require),
                STACK_AS_SNBT.optionalFieldOf("result", Optional.empty()).forGetter(Data::result)
        ).apply(instance, Data::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::carry,
                ByteBufCodecs.optional(ItemStackTemplate.STREAM_CODEC).apply(ByteBufCodecs.list()),
                Data::require,
                ByteBufCodecs.optional(ItemStackTemplate.STREAM_CODEC), Data::result,
                Data::new);
    }

    /**
     * Builds the serializer that reads and writes the vanilla recipe it wraps, plus one block of our own
     * beside it.
     *
     * <p>The vanilla codec does all the real work, which is the point: the file stays a normal shaped or
     * shapeless recipe that someone could still read by hand, with the data parts gathered under a single
     * key rather than scattered through fields the game would not expect them in.
     */
    public static final class Serializer {
        private final RecipeSerializer<InheritingCraftingRecipe> serializer;

        public <T extends CraftingRecipe> Serializer(RecipeSerializer<T> vanilla) {
            // The wrapped recipe is always the shape this serializer was built for. The pairing is fixed
            // here and cannot be got wrong from outside, which is what the cast rests on.
            @SuppressWarnings("unchecked")
            java.util.function.Function<InheritingCraftingRecipe, T> unwrap = recipe -> (T) recipe.base;
            MapCodec<InheritingCraftingRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    vanilla.codec().forGetter(unwrap),
                    Data.CODEC.optionalFieldOf(DATA_KEY, new Data("none", List.of(), Optional.empty()))
                            .forGetter(recipe -> new Data(recipe.carry.key(), wrap(recipe.required),
                                    recipe.resultStack))
            ).apply(instance, (base, data) -> new InheritingCraftingRecipe(
                    base, Carry.of(data.carry()), data.require(), data.result(), this)));
            StreamCodec<RegistryFriendlyByteBuf, InheritingCraftingRecipe> streamCodec = StreamCodec.composite(
                    vanilla.streamCodec(), unwrap,
                    Data.STREAM_CODEC,
                    recipe -> new Data(recipe.carry.key(), wrap(recipe.required), recipe.resultStack),
                    (base, data) -> new InheritingCraftingRecipe(
                            base, Carry.of(data.carry()), data.require(), data.result(), this));
            this.serializer = new RecipeSerializer<>(codec, streamCodec);
        }

        /** The stored form of the required list, where every entry is present. */
        private static List<Optional<ItemStackTemplate>> wrap(List<ItemStackTemplate> templates) {
            return templates.stream().map(Optional::of).toList();
        }

        /**
         * The serializer itself, ready to register.
         *
         * <p>A {@code RecipeSerializer} is a record of two codecs from 26.1.2 rather than an interface to
         * implement, which is simpler — except that the codecs have to hand each recipe the serializer
         * that read it, and a record cannot exist before its own fields. So this class builds the pair
         * first and the record last, and is what the recipe holds on to.
         */
        public RecipeSerializer<InheritingCraftingRecipe> get() {
            return serializer;
        }
    }
}
