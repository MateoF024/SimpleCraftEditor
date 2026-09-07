package org.mateof24.sce.core.compat;

import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.mateof24.sce.core.SceDebug;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Forgets the recipe Polymorph has remembered for each player, after the recipe set has changed.
 *
 * <p>Polymorph lets a player pick which of several recipes an ingredient layout should produce, and
 * remembers that choice as the recipe itself. Before using it again it checks only that it still matches
 * what is in the grid — never that the recipe still exists. Deleting a recipe therefore does not stop it
 * being crafted: the game no longer has it, but the player is still holding one, and it keeps working
 * until they log out. Turning a recipe off is unaffected, which is why only deletion looked broken.
 *
 * <p>Unlike the cache clearing in the state manager, this names a mod, because there is nothing generic
 * left to aim at: the reference lives on a capability of the player, not on the recipe manager, and only
 * Polymorph can say which capability that is. So it goes through Polymorph's own public API, by
 * reflection and only when Polymorph is present, which keeps it off the build entirely and makes it
 * impossible for it to affect anyone who does not have the mod.
 *
 * <p>That API has changed shape across Polymorph's own versions — the entry point was renamed and the
 * player's data went from being returned in an {@link Optional} to being returned or null — so both
 * shapes are accepted, and the one method that matters is found by name rather than by signature. When
 * neither shape fits, this says so once and does nothing further; a deleted recipe then stays craftable
 * for a player until they log out, which is the behaviour without the fix rather than a new fault.
 */
public final class PolymorphRecipeSelection {
    private static final String MOD_ID = "polymorph";

    /** The two entry points Polymorph has used, newest first. */
    private static final String[] ENTRY_POINTS = {"getInstance", "common"};
    /** How the player's data is reached, matched with the entry point above by position. */
    private static final String[] ACCESSORS = {"getPlayerRecipeData", "getRecipeData"};

    private static Object api;
    private static Method accessor;
    private static Method setSelectedRecipe;
    private static boolean resolved;

    private PolymorphRecipeSelection() {
    }

    /** Forgets every player's remembered recipe. Does nothing at all when Polymorph is not installed. */
    public static void clearRemembered(MinecraftServer server) {
        if (!Platform.isModLoaded(MOD_ID) || !resolve()) {
            return;
        }
        int cleared = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                Object data = accessor.invoke(api, player);
                if (data instanceof Optional<?> optional) {
                    data = optional.orElse(null);
                }
                if (data != null) {
                    setSelectedRecipe.invoke(data, new Object[]{null});
                    cleared++;
                }
            } catch (Throwable ignored) {
                // One player failing to be cleared is not worth interrupting the edit over.
            }
        }
        if (cleared > 0) {
            SceDebug.log(SceDebug.Category.COMPAT,
                    "Cleared the recipe Polymorph had remembered for {} player(s), so a deleted recipe stops crafting",
                    cleared);
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return accessor != null;
        }
        resolved = true;
        try {
            Class<?> apiClass = Class.forName("com.illusivesoulworks.polymorph.api.PolymorphApi");
            for (int i = 0; i < ENTRY_POINTS.length && api == null; i++) {
                try {
                    Object candidate = apiClass.getMethod(ENTRY_POINTS[i]).invoke(null);
                    if (candidate == null) {
                        continue;
                    }
                    // Found by name and parameter rather than by declaring type: the accessor is
                    // overloaded for players, block entities and item stacks, so the parameter is what
                    // tells them apart, and scanning covers it wherever it is declared.
                    accessor = accessorOn(candidate, ACCESSORS[i]);
                    api = candidate;
                } catch (ReflectiveOperationException next) {
                    accessor = null;
                }
            }
            if (accessor == null) {
                throw new NoSuchMethodException("no known way to reach a player's recipe data");
            }
            accessor.setAccessible(true);
            setSelectedRecipe = selectedRecipeSetter();
        } catch (Throwable e) {
            api = null;
            accessor = null;
            SceDebug.log(SceDebug.Category.COMPAT,
                    "Polymorph is installed but its API is not one this version knows ({}), so a deleted recipe "
                            + "may keep crafting for a player until they log out", e.toString());
        }
        return accessor != null;
    }

    /** The method taking a single player, wherever on the object's type it happens to be declared. */
    private static Method accessorOn(Object candidate, String name) throws ReflectiveOperationException {
        for (Method method : candidate.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Player.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Finds the setter by name. Its parameter is the recipe, which is a bare recipe on some versions and a
     * wrapper around one on others, so matching on the name is what survives that difference.
     */
    private static Method selectedRecipeSetter() throws ReflectiveOperationException {
        Class<?> recipeData = Class.forName("com.illusivesoulworks.polymorph.api.common.capability.IRecipeData");
        for (Method method : recipeData.getMethods()) {
            if (method.getName().equals("setSelectedRecipe") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("IRecipeData.setSelectedRecipe");
    }
}
