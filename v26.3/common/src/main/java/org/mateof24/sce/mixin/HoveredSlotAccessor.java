package org.mateof24.sce.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads which slot the pointer is over on any container screen.
 *
 * <p>Needed so the editor key works over an item anywhere — the inventory, a chest, the creative menu,
 * this mod's own editor, any other mod's container screen — and not only over a recipe viewer's list.
 * Every one of those is built on slots, so one accessor covers all of them at once.
 *
 * <p>An accessor rather than an injection: the game keeps the answer in a {@code protected} field and
 * offers no way to ask for it from outside, and reading a field cannot change what the screen does.
 * Both versions this mod targets spell the field the same way and neither has ever exposed a getter.
 */
@Mixin(AbstractContainerScreen.class)
public interface HoveredSlotAccessor {
    @Accessor("hoveredSlot")
    Slot sce$hoveredSlot();
}
