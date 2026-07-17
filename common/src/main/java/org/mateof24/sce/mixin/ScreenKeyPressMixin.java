package org.mateof24.sce.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.mateof24.sce.client.SceClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client hook so the editor key works over the JEI/EMI ingredient list while a screen is open (keybinds
 * only fire in-world). If the key is pressed over an item in a viewer, load that item's recipe.
 */
@Mixin(Screen.class)
public class ScreenKeyPressMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sce$loadHoveredRecipe(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (SceClient.tryLoadHoveredRecipe(keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }
}
