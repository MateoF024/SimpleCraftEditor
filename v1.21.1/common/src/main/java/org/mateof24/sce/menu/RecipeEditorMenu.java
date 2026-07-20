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
 * Synced container for the recipe editor. Input and output slots are backed by temporary containers, so
 * items placed there behave exactly like a crafting table (real cursor interaction, splitting, dragging,
 * hotbar swaps, shift-click) and are returned to the player when the screen closes. The player inventory
 * slots are the real inventory. The slot layout depends on the recipe type: a 3x3 grid for crafting, a
 * single input for cooking/stonecutting, and a 2x3 input block with a column of outputs for Create.
 */
public class RecipeEditorMenu extends AbstractContainerMenu {
    // Sized for the largest layout any type uses: mechanical crafting's square grid.
    private final Container grid = new SimpleContainer(RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE);
    private final Container output = new SimpleContainer(4);

    private final ResourceLocation editId;
    private final int mode;
    private final int inputCount;
    private final int outputCount;
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
        this.outputCount = RecipeModes.outputCount(this.mode);

        boolean create = RecipeModes.isCreate(this.mode);
        boolean mechanical = RecipeModes.isMechanicalCrafting(this.mode);
        boolean single = !RecipeModes.isCrafting(this.mode) && !create && !mechanical;
        int columns = mechanical ? RecipeDraft.MECHANICAL_SIZE : 3;
        for (int i = 0; i < inputCount; i++) {
            int x = single ? 60 : 44 + (i % columns) * 18;
            int y = single ? 58 : 42 + (i / columns) * 18;
            addSlot(new Slot(grid, i, x, y));
        }
        for (int i = 0; i < outputCount; i++) {
            // Mechanical crafting has a single result, sat beside its taller grid.
            int x = single ? 140 : (create ? 150 + (i % 2) * 18 : 150);
            int y = single ? 58 : (create ? 42 + (i / 2) * 18 : (mechanical ? 78 : 60));
            addSlot(new Slot(output, i, x, y));
        }

        // The inventory sits below the tag and fluid rows; keep in sync with the panel texture height.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, 39 + col * 18, 158 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 39 + col * 18, 218));
        }
    }

    public int mode() {
        return mode;
    }

    public int inputCount() {
        return inputCount;
    }

    public int outputCount() {
        return outputCount;
    }

    @Nullable
    public ResourceLocation editId() {
        return editId;
    }

    @Nullable
    public RecipeDraft baseDraft() {
        return baseDraft;
    }

    public Slot inputSlot(int index) {
        return slots.get(index);
    }

    public Slot outputSlot(int index) {
        return slots.get(inputCount + index);
    }

    public ItemStack gridItem(int index) {
        return grid.getItem(index);
    }

    public ItemStack outputItem(int index) {
        return output.getItem(index);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int slotsUsed = inputCount + outputCount;
        int invStart = slotsUsed;
        int invEnd = slotsUsed + 36;
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < invStart) {
                if (!moveItemStackTo(stack, invStart, invEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, slotsUsed, false)) {
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
