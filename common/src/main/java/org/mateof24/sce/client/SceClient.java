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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.client.screen.RawRecipeScreen;
import org.mateof24.sce.client.screen.RecipeEditorScreen;
import org.mateof24.sce.client.screen.RecipeManagerScreen;
import org.mateof24.sce.net.SceNetworking;
import org.mateof24.sce.registry.SceMenus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Client entrypoint: keybind, S2C receivers and the tick hook that opens the manager screen. */
@Environment(EnvType.CLIENT)
public final class SceClient {
    private static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.sce.open_manager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.sce");

    // Item under the cursor, supplied by the JEI and EMI integrations when they are present.
    private static final List<Supplier<ItemStack>> HOVERED_PROVIDERS = new ArrayList<>();

    private SceClient() {
    }

    public static void registerHoveredItemProvider(Supplier<ItemStack> provider) {
        HOVERED_PROVIDERS.add(provider);
    }

    /**
     * When the open key is pressed over an item in JEI/EMI's list, open (or reload) the editor with the
     * recipe that produces that item. Returns true if it handled the key.
     */
    public static boolean tryLoadHoveredRecipe(int keyCode, int scanCode) {
        if (!OPEN_MANAGER.matches(keyCode, scanCode)) {
            return false;
        }
        ItemStack hovered = hoveredItem();
        if (hovered.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation recipeId = findRecipeFor(minecraft, hovered);
        if (recipeId == null) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("[SCE] No recipe found for " + hovered.getHoverName().getString()), true);
            }
            return true;
        }
        SceNetworking.sendOpenEditor(recipeId.toString(), -1);
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

    private static ResourceLocation findRecipeFor(Minecraft minecraft, ItemStack target) {
        if (minecraft.level == null) {
            return null;
        }
        RegistryAccess access = minecraft.level.registryAccess();
        for (Recipe<?> recipe : minecraft.level.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem(access);
            if (!result.isEmpty() && ItemStack.isSameItem(result, target)) {
                return recipe.getId();
            }
        }
        return null;
    }

    public static void init() {
        registerReceivers();
        MenuRegistry.registerScreenFactory(SceMenus.RECIPE_EDITOR.get(), RecipeEditorScreen::new);
        KeyMappingRegistry.register(OPEN_MANAGER);
        ClientTickEvent.CLIENT_POST.register(minecraft -> {
            while (OPEN_MANAGER.consumeClick()) {
                if (minecraft.player != null) {
                    minecraft.setScreen(new RecipeManagerScreen());
                }
            }
        });
    }

    private static void registerReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.SYNC, (buf, context) -> {
            List<ClientEditorState.Entry> disabled = readEntries(buf);
            List<ClientEditorState.Entry> generated = readEntries(buf);
            context.queue(() -> {
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
    }

    private static List<ClientEditorState.Entry> readEntries(net.minecraft.network.FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ClientEditorState.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            ItemStack display = buf.readItem();
            boolean flag = buf.readBoolean();
            entries.add(new ClientEditorState.Entry(id, display, flag));
        }
        return entries;
    }
}
