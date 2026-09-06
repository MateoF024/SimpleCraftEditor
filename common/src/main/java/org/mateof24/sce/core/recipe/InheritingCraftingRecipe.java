package org.mateof24.sce.core.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
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
 * editor's slots:
 *
 * <ul>
 *   <li>{@link #required} — stacks that must be present in the grid, matched <em>with</em> their data.
 *       Empty means the recipe matches by item alone, exactly like a vanilla one.</li>
 *   <li>{@link #resultStack} — the result as the author left it, data and all.</li>
 * </ul>
 *
 * <p>{@link Carry} then says where the crafted result's data comes from. Between the two, every
 * combination that means anything can be expressed, and no slot has to be singled out.
 *
 * <p><b>It is still an ordinary crafting recipe.</b> Its type is {@code minecraft:crafting}, inherited
 * from {@link CraftingRecipe}, so a crafting table finds it and JEI, EMI and REI list it in the crafting
 * category with no work on their side. Only matching and assembling differ.
 *
 * <p><b>What it does not do.</b> Carrying data is not the same as running a mod's own code: Sophisticated
 * Backpacks also resizes the new backpack for its tier, and nothing generic can know to do that. For a
 * recipe that already belongs to a mod the right answer is to keep its type, which the editor does on
 * its own.
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
    /** Stacks the grid must contain, data included. Empty when data is not part of the match. */
    private final List<ItemStack> required;
    /** The result exactly as the author left it in the editor; empty when they left it plain. */
    private final ItemStack resultStack;

    private InheritingCraftingRecipe(CraftingRecipe base, Carry carry, List<ItemStack> required,
                                     ItemStack resultStack, Serializer serializer) {
        this.base = base;
        this.carry = carry;
        this.required = List.copyOf(required);
        this.resultStack = resultStack;
        this.serializer = serializer;
    }

    // ------------------------------------------------------------------ matching

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        return base.matches(container, level) && hasRequiredData(container);
    }

    /**
     * Whether the grid holds every stack this recipe insists on, data and all.
     *
     * <p>Each requirement has to be met by a <em>different</em> slot, so a recipe asking for two named
     * chests is not satisfied by one. Position is not checked: the vanilla recipe underneath has already
     * decided the layout is right, and a shapeless recipe has no layout at all.
     */
    private boolean hasRequiredData(CraftingContainer container) {
        if (required.isEmpty()) {
            return true;
        }
        boolean[] taken = new boolean[container.getContainerSize()];
        for (ItemStack wanted : required) {
            int found = -1;
            for (int slot = 0; slot < container.getContainerSize() && found < 0; slot++) {
                if (!taken[slot] && ItemStack.isSameItemSameTags(container.getItem(slot), wanted)) {
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
    public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
        ItemStack result = base.assemble(container, access);
        CompoundTag data = dataFor(container);
        if (data != null && !data.isEmpty()) {
            // Laid on top of whatever the result already had rather than replacing it: the recipe underneath
            // may define data of its own, and this is meant to add to it, never to quietly wipe it.
            CompoundTag existing = result.getTag();
            result.setTag(existing == null ? data : existing.merge(data));
        }
        return result;
    }

    /** The data the crafted result should end up with, or null for a plain one. */
    private CompoundTag dataFor(CraftingContainer container) {
        switch (carry) {
            case RECIPE:
                return recipeData();
            case INGREDIENTS:
                return gridData(container);
            case BOTH: {
                // The author's own decision goes on last, so it wins wherever the two describe the same
                // thing: what they wrote down is deliberate, what an ingredient happens to carry is not.
                CompoundTag merged = gridData(container);
                CompoundTag own = recipeData();
                if (own == null) {
                    return merged;
                }
                return merged == null ? own : merged.merge(own);
            }
            default:
                return null;
        }
    }

    private CompoundTag recipeData() {
        CompoundTag tag = resultStack.getTag();
        return tag == null ? null : tag.copy();
    }

    /**
     * Everything the ingredients were carrying, merged in grid order.
     *
     * <p>Every ingredient contributes, rather than one the author had to single out: a recipe can take
     * data from several items at once, and the ones carrying nothing add nothing. Where two describe the
     * same thing the later slot wins, which is at least a rule someone can predict.
     */
    private CompoundTag gridData(CraftingContainer container) {
        CompoundTag merged = null;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            CompoundTag tag = container.getItem(slot).getTag();
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            merged = merged == null ? tag.copy() : merged.merge(tag.copy());
        }
        return merged;
    }

    // ------------------------------------------------------------------ everything else is the base's

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return base.canCraftInDimensions(width, height);
    }

    /**
     * What a recipe viewer draws as the outcome. Shown carrying the recipe's own data whenever the recipe
     * defines any, because that is what the player is actually going to get.
     */
    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        if (carry != Carry.NONE && !resultStack.isEmpty()) {
            return resultStack;
        }
        return base.getResultItem(access);
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return base.getIngredients();
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
        return base.getRemainingItems(container);
    }

    @Override
    public String getGroup() {
        return base.getGroup();
    }

    @Override
    public CraftingBookCategory category() {
        return base.category();
    }

    @Override
    public boolean showNotification() {
        return base.showNotification();
    }

    @Override
    public ResourceLocation getId() {
        return base.getId();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return serializer;
    }

    // ------------------------------------------------------------------ reading and writing

    /** A stack written as the text of its own save tag, which keeps every kind of data in one field. */
    public static ItemStack readStack(String snbt) {
        try {
            return snbt == null || snbt.isEmpty() ? ItemStack.EMPTY : ItemStack.of(TagParser.parseTag(snbt));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    public static String writeStack(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.save(new CompoundTag()).toString();
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

    /**
     * Reads and writes the vanilla recipe it wraps, plus one block of our own beside it.
     *
     * <p>The vanilla serializer does all the real work, which is the point: the file stays a normal
     * shaped or shapeless recipe that someone could still read by hand, with the data parts gathered
     * under a single key rather than scattered through fields the game would not expect them in.
     */
    public static final class Serializer implements RecipeSerializer<InheritingCraftingRecipe> {
        private final RecipeSerializer<? extends CraftingRecipe> vanilla;

        public Serializer(RecipeSerializer<? extends CraftingRecipe> vanilla) {
            this.vanilla = vanilla;
        }

        @Override
        public InheritingCraftingRecipe fromJson(ResourceLocation id, JsonObject json) {
            CraftingRecipe base = vanilla.fromJson(id, json);
            JsonObject data = json.has(DATA_KEY) && json.get(DATA_KEY).isJsonObject()
                    ? json.getAsJsonObject(DATA_KEY) : new JsonObject();
            Carry carry = Carry.of(data.has("carry") ? data.get("carry").getAsString() : "");
            List<ItemStack> required = new ArrayList<>();
            if (data.has("require") && data.get("require").isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray("require")) {
                    ItemStack stack = readStack(element.getAsString());
                    if (!stack.isEmpty()) {
                        required.add(stack);
                    }
                }
            }
            ItemStack result = readStack(data.has("result") ? data.get("result").getAsString() : "");
            return new InheritingCraftingRecipe(base, carry, required, result, this);
        }

        @Override
        public InheritingCraftingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            CraftingRecipe base = vanilla.fromNetwork(id, buf);
            Carry carry = Carry.of(buf.readUtf());
            int count = buf.readVarInt();
            List<ItemStack> required = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                required.add(buf.readItem());
            }
            return new InheritingCraftingRecipe(base, carry, required, buf.readItem(), this);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, InheritingCraftingRecipe recipe) {
            // The wrapped recipe is always the shape this serializer was built for; the pairing is fixed
            // at construction and cannot be got wrong from outside, which is what the cast rests on.
            @SuppressWarnings("unchecked")
            RecipeSerializer<CraftingRecipe> raw = (RecipeSerializer<CraftingRecipe>) vanilla;
            raw.toNetwork(buf, recipe.base);
            buf.writeUtf(recipe.carry.key());
            buf.writeVarInt(recipe.required.size());
            for (ItemStack stack : recipe.required) {
                buf.writeItem(stack);
            }
            buf.writeItem(recipe.resultStack);
        }
    }
}
