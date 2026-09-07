package org.mateof24.sce.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.state.RecipeOutputIndex;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.menu.RecipeEditorMenu;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static final ResourceLocation REQUEST_RECIPES = channel("request_recipes");
    public static final ResourceLocation OPEN_EDITOR = channel("open_editor");
    public static final ResourceLocation SET_SLOT = channel("set_slot");
    public static final ResourceLocation SYNC = channel("sync");
    public static final ResourceLocation RECIPE_JSON = channel("recipe_json");
    public static final ResourceLocation OPEN_RAW = channel("open_raw");
    public static final ResourceLocation OPEN_SEQUENCE = channel("open_sequence");
    public static final ResourceLocation SAVE_RESULT = channel("save_result");
    public static final ResourceLocation RECIPES_FOR = channel("recipes_for");
    public static final ResourceLocation RECIPE_PATCH = channel("recipe_patch");

    private static final int MAX_JSON = 1024 * 1024;

    /** Last permission answer sent to each player, so a change can be noticed and pushed. */
    private static final Map<java.util.UUID, Boolean> lastPermission = new java.util.HashMap<>();

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
        RecipeStateManager.INSTANCE.setPatchSender(SceNetworking::sendRecipePatch);

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SAVE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            String json = buf.readUtf(MAX_JSON);
            context.queue(() -> handleSave(context.getPlayer(), id, json));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, DISABLE, (buf, context) -> {
            ResourceLocation id = buf.readResourceLocation();
            context.queue(() -> ifAllowed(context.getPlayer(), player -> {
                // Turning off a script-written recipe is as short-lived as editing one: the script puts it
                // back on the next load. Refused for the same reason, and said the same way — and an id
                // nothing answers to is refused as that instead.
                RecipeStateManager.Editability verdict =
                        RecipeStateManager.INSTANCE.editability(player.getServer(), id);
                if (verdict.refusal() != null) {
                    player.sendSystemMessage(Component.translatable(verdict.refusal(), id.toString()));
                    return;
                }
                RecipeStateManager.INSTANCE.disable(player.getServer(), id);
            }));
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
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, REQUEST_RECIPES, (buf, context) -> {
            ResourceLocation itemId = buf.readResourceLocation();
            context.queue(() -> handleRequestRecipes(context.getPlayer(), itemId));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, OPEN_EDITOR, (buf, context) -> {
            String idString = buf.readUtf();
            int mode = buf.readVarInt();
            String seed = buf.readUtf();
            context.queue(() -> handleOpenEditor(context.getPlayer(), idString, mode, seed));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SET_SLOT, (buf, context) -> {
            int slotId = buf.readVarInt();
            ItemStack stack = buf.readItem();
            context.queue(() -> handleSetSlot(context.getPlayer(), slotId, stack));
        });

        PlayerEvent.PLAYER_JOIN.register(SceNetworking::syncTo);
        PlayerEvent.PLAYER_QUIT.register(player -> lastPermission.remove(player.getUUID()));
        TickEvent.SERVER_POST.register(SceNetworking::trackPermissions);
    }

    private static void handleSave(net.minecraft.world.entity.player.Player sender, ResourceLocation id, String json) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            deny(sender);
            return;
        }
        ScePerf.Run perf = ScePerf.start("save from the editor");
        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            player.sendSystemMessage(Component.translatable("sce.msg.parse_fail"));
            sendSaveResult(player, id, false);
            perf.finish("the json would not parse");
            return;
        }
        perf.stage("read the json");
        boolean ok = RecipeStateManager.INSTANCE.saveGenerated(player.getServer(), id, parsed);
        perf.stage("store it and apply it");
        player.sendSystemMessage(Component.translatable(
                ok ? "sce.msg.saved" : "sce.msg.rejected", id.toString()));
        sendSaveResult(player, id, ok);
        perf.stage("answer the player");
        perf.finish("{}, {} characters of json", ok ? "saved" : "rejected", json.length());
    }

    private static void sendSaveResult(ServerPlayer player, ResourceLocation id, boolean ok) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeBoolean(ok);
        NetworkManager.sendToPlayer(player, SAVE_RESULT, buf);
    }

    private static void handleRequestJson(net.minecraft.world.entity.player.Player sender, ResourceLocation id) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        long started = ScePerf.now();
        JsonObject json = RecipeStateManager.INSTANCE.editorJson(id);
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id);
        buf.writeUtf(json == null ? "" : json.toString(), MAX_JSON);
        NetworkManager.sendToPlayer(player, RECIPE_JSON, buf);
        ScePerf.since("answer one request for a recipe's json", started);
    }

    /**
     * Answers "which recipes produce this item". The search lives here rather than on the client for two
     * reasons: the server is the only side that can see every output a recipe declares, including the ones
     * that are not its main result, and from 1.21.11 on the client is not sent the recipes at all.
     */
    /**
     * Sends the recipes this mod has changed to every player, instead of the whole recipe set.
     *
     * <p>See {@code RecipeStateManager#sendChangeToClients} for the measurement behind this: receiving
     * the vanilla update packet makes a recipe viewer rebuild its entire index, which was 1.2 to 1.9
     * seconds of frozen game per edit in a 620-mod pack and did not even show the change until the next
     * {@code /reload}. This carries only what actually differs.
     */
    private static void sendRecipePatch(MinecraftServer server,
                                        Map<ResourceLocation, JsonObject> changed,
                                        Set<ResourceLocation> removed) {
        FriendlyByteBuf buf = buffer();
        if (changed.isEmpty() && removed.isEmpty()) {
            return;
        }
        buf.writeVarInt(changed.size());
        for (Map.Entry<ResourceLocation, JsonObject> entry : changed.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeUtf(entry.getValue().toString(), MAX_JSON);
        }
        buf.writeVarInt(removed.size());
        for (ResourceLocation id : removed) {
            buf.writeResourceLocation(id);
        }
        NetworkManager.sendToPlayers(server.getPlayerList().getPlayers(), RECIPE_PATCH, buf);
    }

    private static void handleRequestRecipes(Player sender, ResourceLocation itemId) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        List<ResourceLocation> recipes = List.of();
        Item item = BuiltInRegistries.ITEM.containsKey(itemId) ? BuiltInRegistries.ITEM.get(itemId) : null;
        if (item != null) {
            recipes = RecipeOutputIndex.INSTANCE.recipesProducing(player.getServer(), item);
        }
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(itemId);
        buf.writeVarInt(recipes.size());
        for (ResourceLocation id : recipes) {
            buf.writeResourceLocation(id);
        }
        NetworkManager.sendToPlayer(player, RECIPES_FOR, buf);
    }

    /**
     * A shapeless recipe with nothing in it that makes the given item, as JSON.
     *
     * <p>Built through the ordinary writer rather than by hand so it comes out in whatever shape this
     * version of the game reads, and so the editor opens it down the same path as any other recipe.
     */
    private static String blankRecipeFor(ResourceLocation id, String itemId) {
        ResourceLocation item = ResourceLocation.tryParse(itemId);
        if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) {
            return "";
        }
        RecipeDraft draft = RecipeDraft.blank(RecipeDraft.Kind.CRAFTING_SHAPELESS);
        draft.id = id;
        draft.result = IngredientValue.item(item);
        draft.resultCount = 1;
        // Left off deliberately: a blank recipe has no data to carry, and on auto the writer would give
        // it this mod's own inheriting type for nothing.
        draft.carry = "none";
        return RecipeCompiler.toJson(draft).toString();
    }

    private static void handleOpenEditor(Player sender, String idString, int requestedMode, String seedResult) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            deny(sender);
            return;
        }
        ScePerf.Run perf = ScePerf.start("open the editor");
        ResourceLocation editId = idString.isEmpty() ? null : ResourceLocation.tryParse(idString);
        String editJson = "";
        int mode = Math.max(0, requestedMode);
        // Only an explicit load (a negative mode) pulls in a stored recipe. Changing type must not quietly
        // adopt whatever recipe happens to share the id sitting in the box.
        if (editId != null && requestedMode < 0) {
            // A recipe a script wrote is refused rather than opened: the script rewrites it on every
            // load, so an edit would hold until the next one and then vanish, and saying so is more use
            // than an editor that appears to work. An id nothing answers to is refused as that — see
            // RecipeStateManager#editability, which is what tells the two apart.
            RecipeStateManager.Editability verdict =
                    RecipeStateManager.INSTANCE.editability(player.getServer(), editId);
            if (verdict.refusal() != null) {
                player.sendSystemMessage(Component.translatable(verdict.refusal(), editId.toString()));
                perf.finish("refused: {}", verdict);
                return;
            }
            perf.stage("check it can be edited");
            JsonObject json = RecipeStateManager.INSTANCE.editorJson(editId);
            perf.stage("find its json");
            if (json != null) {
                editJson = json.toString();
                RecipeDraft draft = RecipeCompiler.fromJson(editId, json);
                perf.stage("work out its type");
                if (draft == null) {
                    // No typed editor for this recipe type: fall back to the raw JSON editor.
                    sendOpenRaw(player, editId, editJson);
                    perf.finish("no typed editor for it, sent the raw json one");
                    return;
                }
                mode = RecipeModes.indexOf(draft);
            }
        }
        if (editJson.isEmpty() && !seedResult.isEmpty()) {
            // Nothing was loaded and an item was named: start the editor on a recipe that makes it.
            editJson = blankRecipeFor(editId, seedResult);
            perf.stage("write a blank recipe for the item");
        }
        mode = RecipeModes.sanitize(mode);
        if (RecipeModes.isSequencedAssembly(mode)) {
            sendOpenSequence(player, editId, editJson);
            perf.finish("sequence editor");
            return;
        }
        MenuRegistry.openExtendedMenu(player, new EditorMenuProvider(editId, editJson, mode));
        perf.stage("open the screen");
        perf.finish("type {}, {} characters of json", mode, editJson.length());
    }

    /** Sequenced assembly is edited on its own screen, so it is handed over instead of a container menu. */
    public static void sendOpenSequence(ServerPlayer player, ResourceLocation id, String json) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id == null ? ResourceLocation.tryParse("sce:new_recipe") : id);
        buf.writeUtf(json, MAX_JSON);
        NetworkManager.sendToPlayer(player, OPEN_SEQUENCE, buf);
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
        if (sender instanceof ServerPlayer player && mayEdit(player)) {
            action.accept(player);
        } else {
            deny(sender);
        }
    }

    private static void deny(net.minecraft.world.entity.player.Player sender) {
        sender.sendSystemMessage(Component.translatable("sce.msg.no_permission"));
    }

    // ------------------------------------------------------------------ server -> client sync

    // ------------------------------------------------------------------ permission

    /**
     * Whether a player may use the editor at all. Two conditions, both required.
     *
     * <p>The first is operator level, which also covers a world with cheats on: opening to LAN with
     * cheats, or a singleplayer world created with them, is what raises the level. The second is the game
     * mode — editing recipes is an authoring act, and doing it while playing survival is almost always a
     * mistake rather than an intent, so an operator in survival or adventure is refused the same as
     * anyone else.
     *
     * <p>Evaluated fresh every time. It is deliberately never cached server-side: a player can be opped,
     * given cheats or switched out of survival at any moment, and the answer has to change with them.
     */
    public static boolean mayEdit(Player player) {
        return player.hasPermissions(2) && (player.isCreative() || player.isSpectator());
    }

    /**
     * Pushes a fresh sync to any player whose permission has changed since the last one.
     *
     * <p>The client hides the editor entirely when it may not be used, and it learns that from the sync.
     * Sending it only on join meant a player opped mid-session, or one switching to creative, kept the
     * old answer until they reconnected — and on a LAN world reconnecting closes the world, so enabling
     * cheats could never take effect at all. Checked once a second, which is imperceptible and costs a
     * permission lookup per player.
     */
    private static void trackPermissions(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        long started = ScePerf.now();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean allowed = mayEdit(player);
            Boolean last = lastPermission.get(player.getUUID());
            if (last == null || last != allowed) {
                lastPermission.put(player.getUUID(), allowed);
                syncTo(player);
            }
        }
        ScePerf.since("check everyone's permission (once a second)", started);
    }

    public static void syncToAll(MinecraftServer server) {
        ScePerf.Run perf = ScePerf.start("sync the editor to every player");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
        RecipeStateManager manager = RecipeStateManager.INSTANCE;
        perf.finish("{} player(s), {} disabled and {} of ours to describe",
                server.getPlayerList().getPlayerCount(),
                manager.state().disabled().size(), manager.state().generated().size());
    }

    /** Pushes the current debug mask to every client, so their screens log under the same categories. */
    public static void syncDebugToAll(MinecraftServer server) {
        syncToAll(server); // the debug mask rides along in the sync packet
    }

    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        long started = ScePerf.now();
        RecipeStateManager manager = RecipeStateManager.INSTANCE;
        FriendlyByteBuf buf = buffer();

        buf.writeBoolean(mayEdit(player)); // whether this player may open the editor at all
        buf.writeVarInt(SceDebug.mask());  // so the client logs the same categories the server does

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
        // One player at a time: the interesting number is what this adds up to across a full server.
        ScePerf.since("build and send one player's editor state", started);
    }

    private static ItemStack disabledDisplay(MinecraftServer server, ResourceLocation id, JsonObject snapshot) {
        if (snapshot == null) {
            return ItemStack.EMPTY;
        }
        long started = ScePerf.now();
        try {
            Recipe<?> recipe = RecipeManager.fromJson(id, snapshot);
            return recipe.getResultItem(server.registryAccess());
        } catch (Exception e) {
            return ItemStack.EMPTY;
        } finally {
            ScePerf.since("work out one disabled recipe's icon", started);
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

    /** Asks the server which recipes produce an item. The answer comes back on {@link #RECIPES_FOR}. */
    public static void sendRequestRecipes(ResourceLocation itemId) {
        FriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(itemId);
        NetworkManager.sendToServer(REQUEST_RECIPES, buf);
    }

    /** Asks the server to open the editor menu; empty id means a fresh recipe, mode -1 means "derive from recipe". */
    public static void sendOpenEditor(String idString, int mode) {
        sendOpenEditor(idString, mode, null);
    }

    /** As above, plus the item a brand-new recipe should start out making. */
    public static void sendOpenEditor(String idString, int mode, ResourceLocation seedResult) {
        FriendlyByteBuf buf = buffer();
        buf.writeUtf(idString);
        buf.writeVarInt(mode);
        buf.writeUtf(seedResult == null ? "" : seedResult.toString());
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
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        if (player.containerMenu instanceof RecipeEditorMenu menu
                && slotId >= 0 && slotId < menu.inputCount() + menu.outputCount()) {
            menu.getSlot(slotId).set(stack.copy());
            menu.broadcastChanges();
        }
    }
}
