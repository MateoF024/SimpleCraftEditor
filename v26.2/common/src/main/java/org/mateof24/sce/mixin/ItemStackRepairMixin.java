package org.mateof24.sce.mixin;

import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the anvil rules answer before the game's own component does.
 *
 * <p>From 1.21.11 there is one place to ask instead of five: {@code AnvilMenu.createResult} calls
 * {@code toRepair.isValidRepairItem(material)}, and that reads the {@code minecraft:repairable}
 * component - a set of items, usually a tag. One method, one hook, and every item is covered, including
 * a mod's own: the component is data, so nothing overrides the method.
 *
 * <p>The hook steps out of the way for an item no rule mentions, which is nearly all of them.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackRepairMixin {
    @Inject(method = "isValidRepairItem", at = @At("HEAD"), cancellable = true)
    private void sce$applyAnvilRules(ItemStack material, CallbackInfoReturnable<Boolean> cir) {
        ItemStack self = (ItemStack) (Object) this;
        AnvilRules.Verdict verdict = AnvilRules.INSTANCE.verdict(self, material);
        if (verdict != AnvilRules.Verdict.UNKNOWN) {
            cir.setReturnValue(verdict == AnvilRules.Verdict.YES);
        }
    }
}
