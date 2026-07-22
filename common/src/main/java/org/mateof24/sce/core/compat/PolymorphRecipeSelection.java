package org.mateof24.sce.core.compat;

import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Recipe;
import org.mateof24.sce.core.SceDebug;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Forgets the recipe Polymorph has remembered for each player, after the recipe set has changed.
 *
 * <p>Polymorph lets a player pick which of several recipes an ingredient layout should produce, and
 * remembers that choice on the player as the {@code Recipe} object itself. Before using it again it
 * checks only that the object still has the right type and still matches what is in the grid — never
 * that the recipe still exists. Deleting a recipe therefore does not stop it being crafted: the game no
 * longer has it, but the player is still holding one, and it keeps working until they log out. Turning a
 * recipe off is unaffected, which is why only deletion ever looked broken.
 *
 * <p>Unlike the cache clearing in the state manager, this names a mod, because there is nothing generic
 * left to aim at: the reference lives on a capability of the player, not on the recipe manager, and only
 * Polymorph can say which capability that is. So it goes through Polymorph's own public API, by
 * reflection and only when Polymorph is present, which keeps it off the build entirely and makes it
 * impossible for it to affect anyone who does not have the mod.
 *
 * <p>Clearing the choice is enough and is what Polymorph itself does with an invalid one: with nothing
 * remembered, the shortcut is skipped and the recipe list is rebuilt from the recipe manager, which by
 * then holds the edited set.
 */
public final class PolymorphRecipeSelection {
    private static final String MOD_ID = "polymorph";

    /** Resolved once, then reused: null means unavailable, and we stop trying. */
    private static Method getRecipeData;
    private static Method setSelectedRecipe;
    private static Object common;
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
                Object data = getRecipeData.invoke(common, player);
                if (data instanceof Optional<?> optional && optional.isPresent()) {
                    setSelectedRecipe.invoke(optional.get(), (Recipe<?>) null);
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
            return getRecipeData != null;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName("com.illusivesoulworks.polymorph.api.PolymorphApi");
            common = api.getMethod("common").invoke(null);
            // Looked up on the interface rather than the implementation: getRecipeData is overloaded for
            // players, block entities and item stacks, and only the interface disambiguates it cleanly.
            Class<?> commonApi = Class.forName("com.illusivesoulworks.polymorph.api.common.base.IPolymorphCommon");
            getRecipeData = commonApi.getMethod("getRecipeData", Player.class);
            Class<?> recipeData = Class.forName("com.illusivesoulworks.polymorph.api.common.capability.IRecipeData");
            setSelectedRecipe = recipeData.getMethod("setSelectedRecipe", Recipe.class);
        } catch (Throwable e) {
            getRecipeData = null;
            SceDebug.log(SceDebug.Category.COMPAT,
                    "Polymorph is installed but its API is not the one expected ({}), so a deleted recipe may keep "
                            + "crafting until the player logs out", e.toString());
        }
        return getRecipeData != null;
    }
}
