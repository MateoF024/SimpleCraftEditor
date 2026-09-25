package org.mateof24.sce.mixin;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the anvil rules answer before the game's own tables do.
 *
 * <p>What decides whether one item mends another is a single virtual call,
 * {@code stack.getItem().isValidRepairItem(toRepair, material)}, made from {@code AnvilMenu.createResult}.
 * On this version there is no tag and no data component behind it: the answer comes from
 * {@code Tiers.getRepairIngredient()} and the armour material tables, in Java. So the only portable place
 * to put a rule is in front of that call.
 *
 * <p>All five declarations are listed, because the answer lives in whichever one the item inherits:
 * {@code Item} for everything plain, and the four that override it. Hooking the call site in the menu
 * instead would fix the anvil and leave every recipe viewer that asks the item directly showing something
 * else — and a viewer disagreeing with the block in front of you is worse than no rule at all.
 *
 * <p>The hook steps out of the way for an item no rule mentions, which is nearly all of them: the rules
 * are a short list of exceptions, not a replacement for the game's table. An item a mod overrides this
 * method for itself is outside all five and keeps its own answer — the same answer the viewers get, so
 * the two still agree.
 */
@Mixin({Item.class, TieredItem.class, ArmorItem.class, ElytraItem.class, ShieldItem.class})
public abstract class ItemRepairMixin {
    @Inject(method = "isValidRepairItem", at = @At("HEAD"), cancellable = true)
    private void sce$applyAnvilRules(ItemStack toRepair, ItemStack material,
                                     CallbackInfoReturnable<Boolean> cir) {
        AnvilRules.Verdict verdict = AnvilRules.INSTANCE.verdict(toRepair, material);
        if (verdict != AnvilRules.Verdict.UNKNOWN) {
            cir.setReturnValue(verdict == AnvilRules.Verdict.YES);
        }
    }
}
