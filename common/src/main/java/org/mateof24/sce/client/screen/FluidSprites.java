package org.mateof24.sce.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.hooks.fluid.FluidStackHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.mateof24.sce.core.edit.IngredientValue;

/**
 * Draws a fluid the way a recipe viewer does: its own still texture, tinted by the colour the fluid
 * declares. Buckets were the obvious stand-in but they only work for fluids that have one, which leaves
 * most modded fluids indistinguishable, so the texture is taken from the fluid itself instead.
 *
 * <p>Both the texture and the tint come from Architectury, which already resolves them per loader
 * ({@code IClientFluidTypeExtensions} on Forge and NeoForge, the fluid render handler on Fabric).
 */
@Environment(EnvType.CLIENT)
public final class FluidSprites {
    private FluidSprites() {
    }

    /**
     * Resolves what a slot's fluid value actually points at. A tag names a set rather than one fluid, so
     * its first member stands in for it — the same compromise a viewer makes before it starts cycling.
     */
    public static Fluid resolve(IngredientValue value) {
        if (value == null || !value.isFluid() || value.id() == null) {
            return Fluids.EMPTY;
        }
        if (!value.isFluidTag()) {
            return BuiltInRegistries.FLUID.get(value.id());
        }
        return BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, value.id()))
                .filter(holders -> holders.size() > 0)
                .map(holders -> holders.get(0).value())
                .orElse(Fluids.EMPTY);
    }

    /** The registry id of a fluid, for turning something dragged in from a viewer back into a value. */
    public static ResourceLocation idOf(Fluid fluid) {
        return fluid == null || fluid == Fluids.EMPTY ? null : BuiltInRegistries.FLUID.getKey(fluid);
    }

    /**
     * Blits the fluid over a 16x16 slot. Returns false when the fluid has no texture to draw, letting the
     * caller keep whatever fallback it had.
     */
    public static boolean render(GuiGraphics graphics, Fluid fluid, int x, int y, float alpha) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }
        TextureAtlasSprite sprite = FluidStackHooks.getStillTexture(fluid);
        if (sprite == null) {
            return false;
        }
        // The tint carries the fluid's identity for anything that reuses the water texture, so it has to be
        // applied rather than drawn white; only the colour channels are taken, alpha stays the caller's.
        int color = FluidStackHooks.getColor(fluid);
        float red = (color >> 16 & 0xFF) / 255.0F;
        float green = (color >> 8 & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(red, green, blue, alpha);
        graphics.blit(x, y, 0, 16, 16, sprite);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        return true;
    }
}
