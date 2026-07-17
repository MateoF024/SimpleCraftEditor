package org.mateof24.sce.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import dev.architectury.registry.menu.MenuRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.menu.RecipeEditorMenu;

import java.util.Map;

/**
 * Client/server messaging for the editor. C2S messages request edits (guarded by operator permission);
 * S2C messages keep each client's view of the disabled/generated sets in sync so the restore UI can list
 * recipes that are no longer in the client's recipe manager.
 */
public final class SceNetworking {
    public static final ResourceLocation SAVE = channel("save");
    public static final ResourceLocation DISABLE = channel("disable");
    public static final ResourceLocation ENABLE = channel("enable");
    public static final ResourceLocation DELETE = channel("delete");
    public static final ResourceLocation REQUEST_JSON = channel("request_json");
    public static final ResourceLocation OPEN_EDITOR = channel("open_editor");
    public static final ResourceLocation SET_SLOT = channel("set_slot");
    public static final ResourceLocation SYNC = channel("sync");
    public static final ResourceLocation RECIPE_JSON = channel("recipe_json");
    public static final ResourceLocation OPEN_RAW = channel("open_raw");
    public static final ResourceLocation SAVE_RESULT = channel("save_result");

    private static final int MAX_JSON = 1024 * 1024;

    private SceNetworking() {
    }

    private static ResourceLocation channel(String path) {
        return new ResourceLocation(SimpleCraftEditor.MOD_ID, path);
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    // ------------------------------------------------------------------ common/server registration

    public static void init() {
        RecipeStateManager.INSTANCE.setChangeListener(SceNetworking::syncToAll);

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SAVE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            String json = buf.readUtf(MAX_JSON);
            context.queue(() -> handleSave(context.getPlayer(), id, json));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, DISABLE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            context.queue(() -> ifAllowed(context.getPlayer(), player ->
                    RecipeStateManager.INSTANCE.disable(player.getServer(), id)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ENABLE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            context.queue(() -> ifAllowed(context.getPlayer(), player ->
                    RecipeStateManager.INSTANCE.enable(player.getServer(), id)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, DELETE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            context.queue(() -> ifAllowed(context.getPlayer(), player ->
                    RecipeStateManager.INSTANCE.deleteGenerated(player.getServer(), id)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, REQUEST_JSON, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            context.queue(() -> handleRequestJson(context.getPlayer(), id));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, OPEN_EDITOR, (buf, context) -> {
            String idString = buf.readUtf();
            int mode = buf.readVarInt();
            context.queue(() -> handleOpenEditor(context.getPlayer(), idString, mode));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SET_SLOT, (buf, context) -> {
            int slotId = buf.readVarInt();
            ItemStack stack = buf.readItem();
            context.queue(() -> handleSetSlot(context.getPlayer(), slotId, stack));
        });

        PlayerEvent.PLAYER_JOIN.register(SceNetworking::syncTo);
    }

    private static void handleSave(net.minecraft.world.entity.player.Player sender, ResourceLocation id, String json) {
        if (!(sender instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            deny(sender);
            return;
        }
        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            player.sendSystemMessage(Component.translatable("sce.msg.parse_fail"));
            sendSaveResult(player, id, false);
            return;
        }
        boolean ok = RecipeStateManager.INSTANCE.saveGenerated(player.getServer(), id, parsed);
        player.sendSystemMessage(Component.translatable(
                ok ? "sce.msg.saved" : "sce.msg.rejected", id.toString()));
        sendSaveResult(player, id, ok);
    }

    private static void sendSaveResult(ServerPlayer player, ResourceLocation id, boolean ok) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeBoolean(ok);
        NetworkManager.sendToPlayer(player, SAVE_RESULT, buf);
    }

    private static void handleRequestJson(net.minecraft.world.entity.player.Player sender, ResourceLocation id) {
        if (!(sender instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            return;
        }
        JsonObject json = RecipeStateManager.INSTANCE.editorJson(id);
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeUtf(json == null ? "" : json.toString(), MAX_JSON);
        NetworkManager.sendToPlayer(player, RECIPE_JSON, buf);
    }

    private static void handleOpenEditor(Player sender, String idString, int requestedMode) {
        if (!(sender instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            deny(sender);
            return;
        }
        ResourceLocation editId = idString.isEmpty() ? null : ResourceLocation.tryParse(idString);
        String editJson = "";
        int mode = Math.max(0, requestedMode);
        if (editId != null) {
            JsonObject json = RecipeStateManager.INSTANCE.editorJson(editId);
            if (json != null) {
                editJson = json.toString();
                if (requestedMode < 0) {
                    RecipeDraft draft = RecipeCompiler.fromJson(editId, json);
                    if (draft == null) {
                        // No typed editor for this recipe type: fall back to the raw JSON editor.
                        sendOpenRaw(player, editId, editJson);
                        return;
                    }
                    mode = RecipeModes.indexOf(draft);
                }
            }
        }
        mode = RecipeModes.sanitize(mode);
        MenuRegistry.openExtendedMenu(player, new EditorMenuProvider(editId, editJson, mode));
    }

    public static void sendOpenRaw(ServerPlayer player, ResourceLocation id, String json) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeUtf(json, MAX_JSON);
        NetworkManager.sendToPlayer(player, OPEN_RAW, buf);
    }

    private record EditorMenuProvider(@Nullable ResourceLocation editId, String editJson, int mode) implements ExtendedMenuProvider {
        @Override
        public void saveExtraData(FriendlyByteBuf buf) {
            buf.writeUtf(editId == null ? "" : editId.toString());
            buf.writeUtf(editJson, MAX_JSON);
            buf.writeVarInt(mode);
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("sce.editor.title");
        }

        @Override
        public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
            return new RecipeEditorMenu(containerId, inventory, editId, editJson, mode);
        }
    }

    private static void ifAllowed(net.minecraft.world.entity.player.Player sender, java.util.function.Consumer<ServerPlayer> action) {
        if (sender instanceof ServerPlayer player && player.hasPermissions(2)) {
            action.accept(player);
        } else {
            deny(sender);
        }
    }

    private static void deny(net.minecraft.world.entity.player.Player sender) {
        sender.sendSystemMessage(Component.translatable("sce.msg.no_permission"));
    }

    // ------------------------------------------------------------------ server -> client sync

    public static void syncToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
    }

    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        RecipeStateManager manager = RecipeStateManager.INSTANCE;
        FriendlyByteBuf buf = buffer();

        buf.writeBoolean(player.hasPermissions(2)); // whether this player may open the editor at all

        Map<ResourceLocation, JsonObject> disabled = manager.state().disabled();
        buf.writeVarInt(disabled.size());
        for (Map.Entry<ResourceLocation, JsonObject> entry : disabled.entrySet()) {
            ItemStack display = disabledDisplay(server, entry.getKey(), entry.getValue());
            buf.writeResourceLocation(entry.getKey());
            buf.writeItem(display);
            buf.writeBoolean(display.isEmpty()); // unresolved
            buf.writeBoolean(false);             // (unused for datapack recipes)
        }

        var generated = manager.state().generated().keySet();
        buf.writeVarInt(generated.size());
        for (ResourceLocation id : generated) {
            buf.writeResourceLocation(id);
            buf.writeItem(manager.generatedResultOf(server, id));
            buf.writeBoolean(manager.wasBaseRecipe(id));                // true = edit of an existing recipe
            buf.writeBoolean(manager.state().isGeneratedDisabled(id));  // toggled off
        }

        NetworkManager.sendToPlayer(player, SYNC, buf);
    }

    private static ItemStack disabledDisplay(MinecraftServer server, ResourceLocation id, JsonObject snapshot) {
        if (snapshot == null) {
            return ItemStack.EMPTY;
        }
        try {
            Recipe<?> recipe = RecipeManager.fromJson(id, snapshot);
            return recipe.getResultItem(server.registryAccess());
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------ client -> server send helpers

    public static void sendSave(ResourceLocation id, String json) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeUtf(json, MAX_JSON);
        NetworkManager.sendToServer(SAVE, buf);
    }

    public static void sendSimple(ResourceLocation channel, ResourceLocation recipeId) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(recipeId);
        NetworkManager.sendToServer(channel, buf);
    }

    /** Asks the server to open the editor menu; empty id means a fresh recipe, mode -1 means "derive from recipe". */
    public static void sendOpenEditor(String idString, int mode) {
        FriendlyByteBuf buf = buffer();
        buf.writeUtf(idString);
        buf.writeVarInt(mode);
        NetworkManager.sendToServer(OPEN_EDITOR, buf);
    }

    /** Places a real item into one of the editor's recipe slots (used by JEI/EMI drag). */
    public static void sendSetSlot(int slotId, ItemStack stack) {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(slotId);
        buf.writeItem(stack);
        NetworkManager.sendToServer(SET_SLOT, buf);
    }

    private static void handleSetSlot(Player sender, int slotId, ItemStack stack) {
        if (!(sender instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            return;
        }
        if (player.containerMenu instanceof RecipeEditorMenu menu
                && slotId >= 0 && slotId < menu.inputCount() + menu.outputCount()) {
            menu.getSlot(slotId).set(stack.copy());
            menu.broadcastChanges();
        }
    }
}
