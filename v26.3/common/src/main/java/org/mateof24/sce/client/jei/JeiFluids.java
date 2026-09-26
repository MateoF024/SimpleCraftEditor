package org.mateof24.sce.client.jei;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.material.Fluid;

import java.lang.reflect.Method;

/**
 * Pulls the fluid out of whatever JEI calls a fluid ingredient.
 *
 * <p>Unlike the rest of JEI's API, fluids have no shared type: Fabric hands out
 * {@code IJeiFluidIngredient}, Forge and NeoForge hand out their own platform {@code FluidStack}. None of
 * those classes exists on the other loader, so naming one here would break the module that this editor
 * shares between them. They do agree on a {@code getFluid()} accessor, which is what this looks for —
 * a deliberate trade of compile-time safety for a single implementation, kept to this one method.
 */
@Environment(EnvType.CLIENT)
final class JeiFluids {
    private static Class<?> cachedOwner;
    private static Method cachedGetter;

    private JeiFluids() {
    }

    /** The fluid an ingredient holds, or null if it is not a fluid ingredient at all. */
    static Fluid of(Object ingredient) {
        if (ingredient == null) {
            return null;
        }
        Method getter = getterFor(ingredient.getClass());
        if (getter == null) {
            return null;
        }
        try {
            Object fluid = getter.invoke(ingredient);
            return fluid instanceof Fluid ? (Fluid) fluid : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Looks up the accessor once per ingredient class. A drag reaches this on every frame it is rendered,
     * so the reflective lookup itself must not be repeated; the class is stable for a given loader.
     */
    private static Method getterFor(Class<?> type) {
        if (type == cachedOwner) {
            return cachedGetter;
        }
        Method found = null;
        try {
            Method method = type.getMethod("getFluid");
            if (Fluid.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                found = method;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            found = null;
        }
        cachedOwner = type;
        cachedGetter = found;
        return found;
    }
}
