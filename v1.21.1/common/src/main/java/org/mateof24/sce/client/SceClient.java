package org.mateof24.sce.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.architectury.registry.menu.MenuRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.client.screen.RawRecipeScreen;
import org.mateof24.sce.client.screen.RecipeEditorScreen;
import org.mateof24.sce.client.screen.RecipeManagerScreen;
import org.mateof24.sce.net.SceNetworking;
import org.mateof24.sce.registry.SceMenus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Client entrypoint: keybind, S2C receivers and the tick hook that opens the manager screen. */
@Environment(EnvType.CLIENT)
public final class SceClient {
    private static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.sce.open_manager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.sce");

    // Item under the cursor, supplied by the JEI and EMI integrations when they are present.
    private static final List<Supplier<ItemStack>> HOVERED_PROVIDERS = new ArrayList<>();

    // Which item the key is currently walking the recipes of, and how far along that list we are.
    private static Item cyclingItem;
    private static int cycleIndex;

    private SceClient() {
    }

    public static void registerHoveredItemProvider(Supplier<ItemStack> provider) {
        HOVERED_PROVIDERS.add(provider);
    }

    /**
     * When the open key is pressed over an item in JEI/EMI's list, open (or reload) the editor with a recipe
     * that produces that item. An item often has several recipes — and for modded items the first one is
     * rarely the one you want — so pressing the key again walks to the next one and names it, until the
     * pointer moves to a different item. Returns true if it handled the key.
     */
    public static boolean tryLoadHoveredRecipe(int keyCode, int scanCode) {
        if (!OPEN_MANAGER.matches(keyCode, scanCode) || !ClientEditorState.canEdit()) {
            return false;
        }
        ItemStack hovered = hoveredItem();
        if (hovered.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<ResourceLocation> recipes = findRecipesFor(minecraft, hovered);
        if (recipes.isEmpty()) {
            cyclingItem = null;
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("sce.msg.no_recipe", hovered.getHoverName()), false);
            }
            return true;
        }
        if (hovered.getItem() != cyclingItem) {
            cyclingItem = hovered.getItem();
            cycleIndex = 0;
        } else {
            cycleIndex = (cycleIndex + 1) % recipes.size();
        }
        ResourceLocation picked = recipes.get(cycleIndex);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "sce.msg.recipe_cycle", cycleIndex + 1, recipes.size(), picked.toString()), false);
        }
        // Keep the pointer where it is: stepping through recipes re-opens the editor each time, and the
        // menu transition would otherwise recenter it between presses.
        RecipeEditorScreen.rememberCursor();
        SceNetworking.sendOpenEditor(picked.toString(), -1);
        return true;
    }

    private static ItemStack hoveredItem() {
        for (Supplier<ItemStack> provider : HOVERED_PROVIDERS) {
            try {
                ItemStack stack = provider.get();
                if (stack != null && !stack.isEmpty()) {
                    return stack;
                }
            } catch (Exception ignored) {
                // a viewer being mid-reload should not break the key press
            }
        }
        return ItemStack.EMPTY;
    }

    /** Every loaded recipe producing {@code target}, sorted so repeated presses walk a stable order. */
    private static List<ResourceLocation> findRecipesFor(Minecraft minecraft, ItemStack target) {
        if (minecraft.level == null) {
            return List.of();
        }
        RegistryAccess access = minecraft.level.registryAccess();
        List<ResourceLocation> found = new ArrayList<>();
        for (RecipeHolder<?> holder : minecraft.level.getRecipeManager().getRecipes()) {
            ItemStack result = holder.value().getResultItem(access);
            if (!result.isEmpty() && ItemStack.isSameItem(result, target)) {
                found.add(holder.id());
            }
        }
        found.sort(Comparator.comparing(ResourceLocation::toString));
        return found;
    }

    public static void init() {
        SceNetworking.setClientRegistryAccess(() -> Minecraft.getInstance().level.registryAccess());
        registerReceivers();
        KeyMappingRegistry.register(OPEN_MANAGER);
        ClientTickEvent.CLIENT_POST.register(minecraft -> {
            while (OPEN_MANAGER.consumeClick()) {
                if (minecraft.player != null && ClientEditorState.canEdit()) {
                    minecraft.setScreen(new RecipeManagerScreen());
                }
            }
        });
    }

    /**
     * Registers the editor's screen. Kept separate from {@link #init()} because it must run once the menu
     * type is registered but before the screen-registration event fires — a window each loader reaches at a
     * different time, so the loader entrypoints call it (Fabric at client init; NeoForge inside
     * {@code RegisterMenuScreensEvent}, where the menu supplier is already resolvable).
     */
    public static void registerEditorScreen() {
        MenuRegistry.registerScreenFactory(SceMenus.RECIPE_EDITOR.get(), RecipeEditorScreen::new);
    }

    private static void registerReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.SYNC, (buf, context) -> {
            boolean canEdit = buf.readBoolean();
            List<ClientEditorState.Entry> disabled = readEntries(buf);
            List<ClientEditorState.Entry> generated = readEntries(buf);
            context.queue(() -> {
                ClientEditorState.setCanEdit(canEdit);
                ClientEditorState.setDisabled(disabled);
                ClientEditorState.setGenerated(generated);
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.RECIPE_JSON, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            String raw = buf.readUtf(1024 * 1024);
            JsonObject json = raw.isEmpty() ? null : JsonParser.parseString(raw).getAsJsonObject();
            context.queue(() -> ClientEditorState.onJsonResponse(id, json));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.OPEN_RAW, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            String json = buf.readUtf(1024 * 1024);
            context.queue(() -> Minecraft.getInstance().setScreen(new RawRecipeScreen(id, json)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.SAVE_RESULT, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            boolean ok = buf.readBoolean();
            context.queue(() -> {
                if (Minecraft.getInstance().screen instanceof RecipeEditorScreen editor) {
                    editor.onSaveResult(id, ok);
                }
            });
        });
    }

    private static List<ClientEditorState.Entry> readEntries(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ClientEditorState.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            ItemStack display = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            boolean flag = buf.readBoolean();
            boolean disabled = buf.readBoolean();
            entries.add(new ClientEditorState.Entry(id, display, flag, disabled));
        }
        return entries;
    }
}
