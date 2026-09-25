package org.mateof24.sce.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.core.anvil.AnvilRule;
import org.mateof24.sce.core.anvil.AnvilRules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The anvil rules as a recipe viewer needs to see them: real items, and the two stacks a repair shows.
 *
 * <p>One place for all three viewers rather than three. What JEI, EMI and REI disagree about is how a
 * recipe is described to them; what a rule <em>means</em> is the same for all of them, and working it
 * out three times is how they come to disagree about that too.
 *
 * <p>The rules are text — an item id or a tag — because a rule outlives the pack that gives its tag
 * meaning. A viewer cannot draw text, so this resolves them, here, each time it is asked: a tag that
 * gains a member after a reload gains it in the viewer too.
 *
 * <p><b>Why the viewers need telling at all.</b> None of them asks the game whether one item repairs
 * another. JEI and EMI walk the tier and armour-material tables directly and build their Anvil category
 * from those; only REI asks {@code isValidRepairItem}, and only for some items. So the hook that makes
 * the anvil obey is invisible to them, and a viewer that quietly disagrees with the block in front of
 * you is worse than a viewer that says nothing.
 */
@Environment(EnvType.CLIENT)
public final class AnvilRuleDisplay {
    /**
     * One rule, resolved.
     *
     * @param targets   the items being mended — one, or every member of a tag
     * @param materials what mends them
     * @param replaces  whether the game's own material stops working, which is what a viewer has to
     *                  stop showing
     */
    public record Entry(List<ItemStack> targets, List<ItemStack> materials, boolean replaces) {
    }

    private AnvilRuleDisplay() {
    }

    /** Every rule that resolves to something, in the order they were written. */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (AnvilRule rule : AnvilRules.INSTANCE.rules()) {
            List<ItemStack> targets = resolve(rule.target());
            List<ItemStack> materials = resolve(rule.material());
            if (!targets.isEmpty() && !materials.isEmpty()) {
                entries.add(new Entry(targets, materials, rule.mode() == AnvilRule.Mode.REPLACE));
            }
        }
        return entries;
    }

    /** The items whose vanilla repair recipes a viewer should stop showing. */
    public static Set<Item> replacedTargets() {
        Set<Item> replaced = new LinkedHashSet<>();
        for (Entry entry : entries()) {
            if (entry.replaces()) {
                for (ItemStack stack : entry.targets()) {
                    replaced.add(stack.getItem());
                }
            }
        }
        return replaced;
    }

    /**
     * The two stacks a repair is shown as: the item worn down, and what it looks like afterwards.
     *
     * <p>Three quarters worn and one material, which is how JEI draws every repair the game itself
     * offers — and it is also the truth about the anvil, where one material mends a quarter of the
     * item's durability. Drawing it any other way would make a rule look like a different kind of
     * thing from the repairs beside it.
     */
    public static ItemStack worn(ItemStack target) {
        ItemStack stack = target.copy();
        stack.setDamageValue(stack.getMaxDamage() * 3 / 4);
        return stack;
    }

    /** The same item after one material has been used on it. */
    public static ItemStack mended(ItemStack target) {
        ItemStack stack = target.copy();
        int quarter = stack.getMaxDamage() / 4;
        stack.setDamageValue(Math.max(0, stack.getMaxDamage() * 3 / 4 - quarter));
        return stack;
    }

    /** Whether this item is one an anvil can mend at all; a rule for anything else can never fire. */
    public static boolean repairable(ItemStack stack) {
        return stack.isDamageableItem();
    }

    /** An item id, or every member of a tag. Empty when nothing answers to it, which is not an error. */
    private static List<ItemStack> resolve(String spec) {
        List<ItemStack> found = new ArrayList<>();
        boolean tagged = spec.startsWith("#");
        Identifier id = Identifier.tryParse(tagged ? spec.substring(1) : spec);
        if (id == null) {
            return found;
        }
        if (tagged) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            BuiltInRegistries.ITEM.getTagOrEmpty(tag)
                    .forEach(holder -> found.add(new ItemStack(holder.value())));
        } else {
            BuiltInRegistries.ITEM.get(id).ifPresent(holder -> found.add(new ItemStack(holder.value())));
        }
        return found;
    }
}
