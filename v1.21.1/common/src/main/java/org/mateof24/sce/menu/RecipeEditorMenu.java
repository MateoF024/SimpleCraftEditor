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
 * single input for cooking/stonecutting, and for Create whatever shape its own recipe class allows,
 * which is anything from one slot in and one out to nine items plus two tanks in and six out.
 */
public class RecipeEditorMenu extends AbstractContainerMenu {
    // Sized for the largest layout any type uses — mechanical crafting's square grid on one side, Bulk
    // Washing's twelve results on the other — and taken from the table so that adding a type with more
    // slots than these cannot quietly drop the surplus.
    private final Container grid = new SimpleContainer(
            Math.max(RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE, RecipeModes.maxInputCount()));
    private final Container output = new SimpleContainer(RecipeModes.maxOutputCount());

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

        // Slot positions come from the shared layout so they line up with the widgets the screen puts
        // around them, which are placed from the same numbers.
        EditorLayout layout = new EditorLayout(this.mode);
        for (int i = 0; i < inputCount; i++) {
            addSlot(slot(grid, i, layout.inputSlotX(i), layout.inputSlotY(i),
                    RecipeModes.isFluidInput(this.mode, i)));
        }
        for (int i = 0; i < outputCount; i++) {
            addSlot(slot(output, i, layout.outputSlotX(i), layout.outputSlotY(i),
                    RecipeModes.isFluidOutput(this.mode, i)));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, 39 + col * 18, EditorLayout.INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 39 + col * 18, EditorLayout.HOTBAR_Y));
        }
    }

    /**
     * A recipe slot. A fluid one takes no item at all: what goes in it is a quantity typed into the value
     * row, and letting a bucket be dropped there would look like it had worked when nothing had been set.
     */
    private static Slot slot(Container container, int index, int x, int y, boolean fluid) {
        return fluid ? new Slot(container, index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        } : new Slot(container, index, x, y);
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
