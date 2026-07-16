package org.mateof24.sce.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.registry.SceMenus;

/**
 * Synced container for the recipe editor. The input slots and the output are backed by temporary
 * containers, so items placed there behave exactly like a crafting table (real cursor interaction,
 * splitting, dragging, hotbar swaps, shift-click) and are returned to the player when the screen closes.
 * The player inventory slots are the real inventory. The slot layout depends on the recipe type: a 3x3
 * grid for crafting, a single input for cooking/stonecutting. Tag ingredients and prefilled recipes are
 * drawn as ghosts client-side; they are never physical items, so they never dupe.
 */
public class RecipeEditorMenu extends AbstractContainerMenu {
    private final Container grid = new SimpleContainer(9);
    private final Container output = new SimpleContainer(1);

    private final ResourceLocation editId;
    private final int mode;
    private final int inputCount;
    @Nullable
    private final RecipeDraft baseDraft;

    /** Client factory (from {@link dev.architectury.registry.menu.MenuRegistry#ofExtended}). */
    public RecipeEditorMenu(int containerId, Inventory inventory, FriendlyByteBuf extraData) {
        this(containerId, inventory, parseId(extraData.readUtf()), extraData.readUtf(1024 * 1024), extraData.readVarInt());
    }

    /** Server factory. */
    public RecipeEditorMenu(int containerId, Inventory inventory, @Nullable ResourceLocation editId, String editJson, int mode) {
        super(SceMenus.RECIPE_EDITOR.get(), containerId);
        this.editId = editId;
        this.mode = RecipeModes.clamp(mode);
        this.baseDraft = parseDraft(editId, editJson);
        this.inputCount = RecipeModes.inputCount(this.mode);

        if (RecipeModes.isCrafting(this.mode)) {
            for (int i = 0; i < 9; i++) {
                addSlot(new Slot(grid, i, 44 + (i % 3) * 18, 42 + (i / 3) * 18));
            }
            addSlot(new Slot(output, 0, 150, 60));
        } else {
            addSlot(new Slot(grid, 0, 60, 58));
            addSlot(new Slot(output, 0, 140, 58));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, 39 + col * 18, 120 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 39 + col * 18, 180));
        }
    }

    public int mode() {
        return mode;
    }

    public int inputCount() {
        return inputCount;
    }

    @Nullable
    public ResourceLocation editId() {
        return editId;
    }

    @Nullable
    public RecipeDraft baseDraft() {
        return baseDraft;
    }

    /** Screen-relative position of an input slot. */
    public Slot inputSlot(int index) {
        return slots.get(index);
    }

    public Slot outputSlot() {
        return slots.get(inputCount);
    }

    public ItemStack gridItem(int index) {
        return grid.getItem(index);
    }

    public ItemStack outputItem() {
        return output.getItem(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int invStart = inputCount + 1;
        int invEnd = invStart + 36;
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < invStart) {
                if (!moveItemStackTo(stack, invStart, invEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, inputCount + 1, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, grid);
        clearContainer(player, output);
    }

    private static ResourceLocation parseId(String raw) {
        return raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
    }

    @Nullable
    private static RecipeDraft parseDraft(@Nullable ResourceLocation id, String json) {
        if (id == null || json.isEmpty()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return RecipeCompiler.fromJson(id, object);
        } catch (Exception e) {
            return null;
        }
    }
}
