package org.mateof24.sce.core.anvil;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/**
 * One rule about what repairs what: <em>this item</em> can be mended with <em>that material</em>.
 *
 * <p>Both sides are written the way every other field in this editor is written — an item id, or an item
 * tag with a leading {@code #} — and are kept as text rather than resolved here. A rule may name a tag
 * that a datapack has not loaded yet, or an item from a mod the player adds later; resolving at the
 * moment the anvil asks means such a rule starts working when its item shows up instead of having been
 * quietly dropped when the file was read.
 *
 * <p>{@link Mode} is the whole difference between "the iron sword is mended with iron blocks
 * <em>instead of</em> ingots" and "<em>as well as</em>".
 */
public record AnvilRule(String target, String material, Mode mode) {
    public enum Mode {
        /** The material named here is the only thing that mends the target now. */
        REPLACE,
        /** One more thing that mends it, on top of whatever already did. */
        ADD;

        /** The name written in the file, and read back leniently so a hand-edited file still loads. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Mode of(String name) {
            return "replace".equalsIgnoreCase(name) ? REPLACE : ADD;
        }
    }

    /** Whether this rule is worth keeping: a rule missing either half says nothing. */
    public boolean isComplete() {
        return target != null && !target.isBlank() && material != null && !material.isBlank();
    }

    /** Whether the item being mended is the one this rule is about. */
    public boolean matchesTarget(ItemStack stack) {
        return matches(target, stack);
    }

    /** Whether what the player put in the second slot is what this rule allows. */
    public boolean matchesMaterial(ItemStack stack) {
        return matches(material, stack);
    }

    /**
     * Whether a stack answers to one of these specs.
     *
     * <p>A leading {@code #} means a tag. A tag that no datapack defines matches nothing, which is the
     * right answer rather than an error: the rule is simply not in force until something defines it.
     */
    public static boolean matches(String spec, ItemStack stack) {
        if (spec == null || spec.isBlank() || stack.isEmpty()) {
            return false;
        }
        boolean tagged = spec.startsWith("#");
        Identifier id = Identifier.tryParse(tagged ? spec.substring(1) : spec);
        if (id == null) {
            return false;
        }
        return tagged ? stack.is(TagKey.create(Registries.ITEM, id))
                : id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
