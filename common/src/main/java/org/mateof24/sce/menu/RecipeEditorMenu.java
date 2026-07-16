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
import org.mateof24.sce.registry.SceMenus;

/**
 * Synced container for the recipe editor. The 3x3 input grid and the output are backed by temporary
 * containers, so items placed there behave exactly like a crafting table (real cursor interaction,
 * splitting, dragging, hotbar swaps, shift-click) and are returned to the player when the screen closes.
 * The player inventory slots are the real inventory. Tag ingredients and prefilled recipes are drawn as
 * ghosts client-side (see {@code #baseDraft()}); they are never physical items, so they never dupe.
 */
public class RecipeEditorMenu extends AbstractContainerMenu {
    public static final int GRID_SIZE = 9;
    private static final int GRID_START = 0;
    private static final int OUTPUT_SLOT = 9;
    private static final int INV_START = 10;
    private static final int INV_END = INV_START + 36;

    private final Container grid = new SimpleContainer(GRID_SIZE);
    private final Container output = new SimpleContainer(1);

    private final ResourceLocation editId;
    @Nullable
    private final RecipeDraft baseDraft;

    /** Client factory (from {@link dev.architectury.registry.menu.MenuRegistry#ofExtended}). */
    public RecipeEditorMenu(int containerId, Inventory inventory, FriendlyByteBuf extraData) {
        this(containerId, inventory, parseId(extraData.readUtf()), extraData.readUtf(1024 * 1024));
    }

    /** Server factory. */
    public RecipeEditorMenu(int containerId, Inventory inventory, @Nullable ResourceLocation editId, String editJson) {
        super(SceMenus.RECIPE_EDITOR.get(), containerId);
        this.editId = editId;
        this.baseDraft = parseDraft(editId, editJson);

        for (int i = 0; i < GRID_SIZE; i++) {
            addSlot(new Slot(grid, i, 34 + (i % 3) * 18, 42 + (i / 3) * 18));
        }
        addSlot(new Slot(output, 0, 128, 60));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, 24 + col * 18, 120 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 24 + col * 18, 180));
        }
    }

    @Nullable
    public ResourceLocation editId() {
        return editId;
    }

    @Nullable
    public RecipeDraft baseDraft() {
        return baseDraft;
    }

    public ItemStack gridItem(int index) {
        return grid.getItem(index);
    }

    public ItemStack outputItem() {
        return output.getItem(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < INV_START) {
                if (!moveItemStackTo(stack, INV_START, INV_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, GRID_START, OUTPUT_SLOT + 1, false)) {
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
