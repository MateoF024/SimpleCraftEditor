package org.mateof24.sce.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.client.screen.RecipeManagerScreen;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.List;

/** Client entrypoint: keybind, S2C receivers and the tick hook that opens the manager screen. */
@Environment(EnvType.CLIENT)
public final class SceClient {
    private static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.sce.open_manager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.sce");

    private SceClient() {
    }

    public static void init() {
        registerReceivers();
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
            List<ClientEditorState.Entry> disabled = readEntries(buf, true);
            List<ClientEditorState.Entry> generated = readEntries(buf, false);
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
    }

    private static List<ClientEditorState.Entry> readEntries(net.minecraft.network.FriendlyByteBuf buf, boolean withBroken) {
        int count = buf.readVarInt();
        List<ClientEditorState.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            ItemStack display = buf.readItem();
            boolean broken = withBroken && buf.readBoolean();
            entries.add(new ClientEditorState.Entry(id, display, broken));
        }
        return entries;
    }
}
