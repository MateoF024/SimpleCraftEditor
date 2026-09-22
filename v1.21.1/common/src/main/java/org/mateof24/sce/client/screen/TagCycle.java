package org.mateof24.sce.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shows what a tag actually holds, by walking through it.
 *
 * <p>A tag ingredient names a set, not an item, and the editor used to stand one in with a name tag —
 * which says "this is a tag" and nothing else, and reads to a lot of people as "this recipe needs a name
 * tag". A recipe viewer solves this by cycling the slot through the tag's members, and that is what this
 * does: every {@link #PERIOD} the slot moves on, so given a moment the whole tag goes past.
 *
 * <p>It matters more than it looks. Cobblemon's seasoning tags run from 22 to 120 items, {@code c:foods}
 * runs to hundreds, and an author choosing a tag they did not write has no other way to see what they are
 * letting in.
 *
 * <p>Each tag is resolved once and kept. This is asked on every frame, by every slot on screen.
 */
@Environment(EnvType.CLIENT)
public final class TagCycle {
    /** Long enough to read an icon, short enough that a long tag still gets all the way round. */
    private static final long PERIOD = 1500L;

    private static final Map<ResourceLocation, List<ItemStack>> ITEMS = new HashMap<>();
    private static final Map<ResourceLocation, List<Fluid>> FLUIDS = new HashMap<>();
    private static final Map<ResourceLocation, List<ItemStack>> BLOCKS = new HashMap<>();
    /** Keyed by the ingredient's own JSON, which is the only name a shape like this has. */
    private static final Map<String, List<ItemStack>> RAW = new HashMap<>();

    private TagCycle() {
    }

    /** Throws the resolved tags away; called when a screen opens, in case the tags were reloaded. */
    public static void forget() {
        ITEMS.clear();
        FLUIDS.clear();
        BLOCKS.clear();
        RAW.clear();
    }

    /** Which step of the walk we are on. Shared by every slot, so a screen full of tags moves as one. */
    private static int step() {
        return (int) (Util.getMillis() / PERIOD);
    }

    // ------------------------------------------------------------------ items

    /** Every item in the tag, in registry order. Empty for a tag that does not exist. */
    public static List<ItemStack> items(ResourceLocation tag) {
        if (tag == null) {
            return List.of();
        }
        List<ItemStack> cached = ITEMS.get(tag);
        if (cached != null) {
            return cached;
        }
        List<ItemStack> found = new ArrayList<>();
        Optional<HolderSet.Named<Item>> holders =
                BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tag));
        if (holders.isPresent()) {
            for (Holder<Item> holder : holders.get()) {
                found.add(new ItemStack(holder.value()));
            }
        }
        List<ItemStack> result = List.copyOf(found);
        ITEMS.put(tag, result);
        return result;
    }

    /**
     * The one item on show for this tag right now, as a copy — the caller sets a count on it, and the
     * cached list has to survive that.
     */
    public static ItemStack item(ResourceLocation tag) {
        List<ItemStack> all = items(tag);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(Math.floorMod(step(), all.size())).copy();
    }

    /**
     * The group of {@code slots} items on show right now, paging through the tag.
     *
     * <p>{@code keep} narrows it to the ones that mean something where they are being shown; if it
     * narrows it to nothing, the whole tag is shown instead, because an empty row says less than a row
     * of things that will not do anything.
     */
    public static List<ItemStack> window(ResourceLocation tag, int slots, Predicate<ItemStack> keep) {
        List<ItemStack> all = items(tag);
        if (keep != null && !all.isEmpty()) {
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack stack : all) {
                if (keep.test(stack)) {
                    kept.add(stack);
                }
            }
            if (!kept.isEmpty()) {
                all = kept;
            }
        }
        if (slots <= 0 || all.size() <= slots) {
            return all;
        }
        int pages = (all.size() + slots - 1) / slots;
        int from = Math.floorMod(step(), pages) * slots;
        return all.subList(from, Math.min(from + slots, all.size()));
    }

    // ------------------------------------------------------------------ fluids

    public static List<Fluid> fluids(ResourceLocation tag) {
        if (tag == null) {
            return List.of();
        }
        List<Fluid> cached = FLUIDS.get(tag);
        if (cached != null) {
            return cached;
        }
        List<Fluid> found = new ArrayList<>();
        Optional<HolderSet.Named<Fluid>> holders =
                BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, tag));
        if (holders.isPresent()) {
            for (Holder<Fluid> holder : holders.get()) {
                if (holder.value() != Fluids.EMPTY) {
                    found.add(holder.value());
                }
            }
        }
        List<Fluid> result = List.copyOf(found);
        FLUIDS.put(tag, result);
        return result;
    }

    /** The fluid on show for this tag right now, or empty for a tag with none. */
    public static Fluid fluid(ResourceLocation tag) {
        List<Fluid> all = fluids(tag);
        return all.isEmpty() ? Fluids.EMPTY : all.get(Math.floorMod(step(), all.size()));
    }

    // ------------------------------------------------------------------ carried-through ingredients

    /**
     * What an ingredient this editor does not model actually admits, flattened into items and walked
     * through like a tag.
     *
     * <p>Create's compatibility recipes wrap another mod's item in {@code neoforge:compound}, write
     * alternatives as a bare array, and name block tags that are not item tags. A slot showing only the
     * first of those says less than it should: a recipe viewer shows every option in turn, and an author
     * comparing the two has no way to tell the editor is keeping the rest.
     */
    public static List<ItemStack> rawOptions(JsonElement ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        String key = ingredient.toString();
        List<ItemStack> cached = RAW.get(key);
        if (cached != null) {
            return cached;
        }
        List<ItemStack> found = new ArrayList<>();
        collect(ingredient, found);
        List<ItemStack> result = List.copyOf(found);
        RAW.put(key, result);
        return result;
    }

    /** The one option on show right now, as a copy, or empty when nothing inside it names an item. */
    public static ItemStack rawItem(JsonElement ingredient) {
        List<ItemStack> all = rawOptions(ingredient);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(Math.floorMod(step(), all.size())).copy();
    }

    /**
     * Walks whatever shape the ingredient has. {@code ingredients} and {@code values} are the two names
     * ingredient types use for the list inside them; a {@code tag} is tried as an item tag and then as a
     * block tag, which is how {@code neoforge:block_tag} resolves without this having to know its name.
     */
    private static void collect(JsonElement element, List<ItemStack> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement option : element.getAsJsonArray()) {
                collect(option, out);
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            // A bare id, or "#tag": how a set of items is spelled inside NeoForge's component
            // ingredient, and how vanilla itself spells an ingredient from 1.21.11 on.
            addNamed(element.getAsString(), out);
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        // "anything except this" - walking into it would show precisely the items it refuses.
        String type = object.has("type") && object.get("type").isJsonPrimitive()
                ? object.get("type").getAsString() : "";
        if (type.endsWith(":not")) {
            return;
        }
        if (object.has("item") && object.get("item").isJsonPrimitive()) {
            addItem(ResourceLocation.tryParse(object.get("item").getAsString()), out);
        } else if (object.has("tag") && object.get("tag").isJsonPrimitive()) {
            addTag(ResourceLocation.tryParse(object.get("tag").getAsString()), out);
        }
        if (object.has("items")) {
            // NeoForge's component ingredient names its set of items under this one, as a string, a
            // "#tag" or a list of either - not as the objects every other shape uses.
            collect(object.get("items"), out);
        }
        // And then everything nested, whatever it is called. There is no single spelling to look for:
        // Create writes a compound ingredient's options under "ingredients" and Farmer's Delight writes
        // the same NeoForge ingredient's options under "children"; Fabric's own wrapper uses
        // "ingredients" again. Walking every nested array and object costs nothing - only the item and
        // tag keys above ever add anything - and it means the next mod's spelling works too.
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement child = entry.getValue();
            if ((child.isJsonArray() || child.isJsonObject()) && !entry.getKey().equals("items")) {
                collect(child, out);
            }
        }
    }

    /** One id, with a leading {@code #} meaning a tag of them. */
    private static void addNamed(String raw, List<ItemStack> out) {
        boolean tagged = raw.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tagged ? raw.substring(1) : raw);
        if (tagged) {
            addTag(id, out);
        } else {
            addItem(id, out);
        }
    }

    private static void addItem(ResourceLocation id, List<ItemStack> out) {
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item != null && item != Items.AIR) {
            out.add(new ItemStack(item));
        }
    }

    /** A tag's members - as items, or as blocks when no item tag of that name exists. */
    private static void addTag(ResourceLocation id, List<ItemStack> out) {
        if (id == null) {
            return;
        }
        List<ItemStack> members = items(id);
        if (members.isEmpty()) {
            members = blocks(id);
        }
        out.addAll(members);
    }

    /** The items of the blocks in a block tag, for the ingredient types that name one. */
    private static List<ItemStack> blocks(ResourceLocation tag) {
        List<ItemStack> cached = BLOCKS.get(tag);
        if (cached != null) {
            return cached;
        }
        List<ItemStack> found = new ArrayList<>();
        Optional<HolderSet.Named<Block>> holders =
                BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tag));
        if (holders.isPresent()) {
            for (Holder<Block> holder : holders.get()) {
                ItemStack stack = new ItemStack(holder.value());
                if (!stack.isEmpty()) {
                    found.add(stack);
                }
            }
        }
        List<ItemStack> result = List.copyOf(found);
        BLOCKS.put(tag, result);
        return result;
    }
}
