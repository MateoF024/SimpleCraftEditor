package org.mateof24.sce.client;

import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Asks Cobblemon whether an item would actually do anything as seasoning, without depending on it.
 *
 * <p>A campfire pot recipe names a tag of items a player may add, but only some of them mean anything:
 * the item has to be in Cobblemon's {@code seasonings} data registry, and one of the recipe's own
 * processors has to want what it carries. Cobblemon's JEI category filters its preview by exactly that
 * pair, and this editor should show the same thing.
 *
 * <p>Neither answer can be worked out from the recipe file: both live in Cobblemon. So they are asked of
 * Cobblemon by reflection, which is a trade this module makes on purpose — naming those classes would
 * turn a mod that is optional everywhere else into a compile dependency of the shared code, and this is
 * a hint in a preview, not a decision. Every lookup is done once and every failure is final: if anything
 * about Cobblemon is not where this expects, the editor simply shows the whole tag, which is what it did
 * before and is never wrong, only less helpful.
 *
 * <p>The names reached for are Cobblemon's own and are not remapped by any loader; the {@code ItemStack}
 * in the signatures is whichever class the running loader uses, which is the same one this module is
 * compiled against.
 */
@Environment(EnvType.CLIENT)
public final class CobblemonSeasonings {
    private static final String MOD_ID = "cobblemon";
    private static final String SEASONINGS = "com.cobblemon.mod.common.api.cooking.Seasonings";
    private static final String PROCESSOR = "com.cobblemon.mod.common.item.crafting.SeasoningProcessor";

    private static boolean looked;
    private static Object seasonings;
    private static Method isSeasoning;
    private static Method consumesItem;
    private static Map<?, ?> processors;

    private CobblemonSeasonings() {
    }

    /** Whether Cobblemon is here and everything this needs was found. */
    public static boolean available() {
        look();
        return seasonings != null;
    }

    /**
     * Whether this item, added to a recipe that absorbs these properties, would change the dish.
     *
     * <p>False when Cobblemon cannot be asked, so a caller has to decide what to do without an answer
     * rather than be told "no" by a lookup that never happened.
     */
    public static boolean contributes(ItemStack stack, List<String> absorbs) {
        if (!available() || stack == null || stack.isEmpty() || absorbs.isEmpty()) {
            return false;
        }
        try {
            if (!(Boolean) isSeasoning.invoke(seasonings, stack)) {
                return false;
            }
            for (String name : absorbs) {
                Object processor = processors.get(name);
                if (processor != null && (Boolean) consumesItem.invoke(processor, stack)) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            forget();
        }
        return false;
    }

    private static void look() {
        if (looked) {
            return;
        }
        looked = true;
        if (!Platform.isModLoaded(MOD_ID)) {
            return;
        }
        try {
            Class<?> registry = Class.forName(SEASONINGS);
            Object instance = registry.getField("INSTANCE").get(null);
            Method seasoningCheck = registry.getMethod("isSeasoning", ItemStack.class);

            Class<?> processor = Class.forName(PROCESSOR);
            Object companion = processor.getField("Companion").get(null);
            Object table = companion.getClass().getMethod("getProcessors").invoke(companion);
            Method consumes = processor.getMethod("consumesItem", ItemStack.class);

            if (!(table instanceof Map<?, ?> map)) {
                return;
            }
            // Assigned last and together: half a set of handles is worse than none, because the checks
            // above would pass and the calls below would not.
            seasonings = instance;
            isSeasoning = seasoningCheck;
            consumesItem = consumes;
            processors = map;
        } catch (ReflectiveOperationException | RuntimeException e) {
            forget();
        }
    }

    private static void forget() {
        seasonings = null;
        isSeasoning = null;
        consumesItem = null;
        processors = null;
    }
}
