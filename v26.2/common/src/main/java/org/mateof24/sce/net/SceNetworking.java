package org.mateof24.sce.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.registry.menu.ExtendedMenuProvider;
import dev.architectury.registry.menu.MenuRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;
import org.mateof24.sce.SimpleCraftEditor;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;
import org.mateof24.sce.core.edit.IngredientValue;
import org.mateof24.sce.core.edit.RecipeCompiler;
import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.core.state.RecipeOutputIndex;
import org.mateof24.sce.core.anvil.AnvilRule;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.menu.RecipeEditorMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Client/server messaging for the editor. C2S messages request edits (guarded by operator permission);
 * S2C messages keep each client's view of the disabled/generated sets in sync so the restore UI can list
 * recipes that are no longer in the client's recipe manager.
 *
 * <p>On 1.21.1 network buffers are {@link RegistryFriendlyByteBuf} and {@link ItemStack}s are written with
 * {@code ItemStack.OPTIONAL_STREAM_CODEC}. A buffer needs the registry access to serialize items; the
 * client supplies its own through {@link #setClientRegistryAccess} so this common class never touches a
 * client-only type.
 */
public final class SceNetworking {
    public static final Identifier SAVE = channel("save");
    public static final Identifier DISABLE = channel("disable");
    public static final Identifier ENABLE = channel("enable");
    public static final Identifier DELETE = channel("delete");
    public static final Identifier REQUEST_JSON = channel("request_json");
    public static final Identifier REQUEST_RECIPES = channel("request_recipes");
    public static final Identifier REQUEST_RECIPE_IDS = channel("request_recipe_ids");
    public static final Identifier OPEN_EDITOR = channel("open_editor");
    public static final Identifier SET_SLOT = channel("set_slot");
    public static final Identifier SYNC = channel("sync");
    public static final Identifier RECIPE_JSON = channel("recipe_json");
    public static final Identifier OPEN_RAW = channel("open_raw");
    public static final Identifier OPEN_SEQUENCE = channel("open_sequence");
    public static final Identifier SAVE_RESULT = channel("save_result");
    public static final Identifier RECIPES_FOR = channel("recipes_for");
    public static final Identifier RECIPE_IDS = channel("recipe_ids");
    /** The anvil rules, sent back whole after any edit to them. */
    public static final Identifier SET_ANVIL_RULES = channel("set_anvil_rules");

    private static final int MAX_JSON = 1024 * 1024;
    /**
     * How many recipe ids travel in one packet. A payload is capped at a megabyte and an id cannot be
     * longer than about half a kilobyte, so a batch this size cannot reach the cap however the ids are
     * spelled; a heavily modded server has tens of thousands of them and needs the batching.
     */
    private static final int ID_BATCH = 1000;

    private static Supplier<RegistryAccess> clientRegistryAccess = () -> null;
    /** Last permission answer sent to each player, so a change can be noticed and pushed. */
    private static final Map<java.util.UUID, Boolean> lastPermission = new java.util.HashMap<>();

    private SceNetworking() {
    }

    private static Identifier channel(String path) {
        return Identifier.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, path);
    }

    /** Set from the client entrypoint so C2S buffers can serialize items with the client's registries. */
    public static void setClientRegistryAccess(Supplier<RegistryAccess> access) {
        clientRegistryAccess = access;
    }

    private static RegistryFriendlyByteBuf clientBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), clientRegistryAccess.get());
    }

    private static RegistryFriendlyByteBuf serverBuffer(Player player) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
    }

    // ------------------------------------------------------------------ common/server registration

    public static void init() {
        RecipeStateManager.INSTANCE.setChangeListener(SceNetworking::syncToAll);

        // Every channel below travels inside one payload per direction — see SceRawNetwork, which is
        // where 26.2's break with the raw-buffer API is absorbed.
        SceRawNetwork.registerServerSide();

        // Declare the server-to-client payload, but only where nothing else will. Registering a
        // receiver declares its type as a side effect, so on a client the client entrypoint has already
        // done it, and declaring it twice makes NeoForge refuse the duplicate and the game not start. A
        // dedicated server never runs that entrypoint, so there it would otherwise never exist and the
        // login sync threw inside Architectury, dropping the player with "Invalid player data".
        //
        // The asymmetry is the point: declare here exactly when there is no receiver to do it.
        if (Platform.getEnvironment() == Env.SERVER) {
            SceRawNetwork.declareToClient();
        }

        SceRawNetwork.toServer(SAVE, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            String json = buf.readUtf(MAX_JSON);
            context.queue(() -> handleSave(context.getPlayer(), id, json));
        });
        SceRawNetwork.toServer(DISABLE, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            context.queue(() -> ifAllowed(context.getPlayer(), player -> {
                // Turning off a script-written recipe is as short-lived as editing one: the script puts it
                // back on the next load. Refused for the same reason, and said the same way — and an id
                // nothing answers to is refused as that instead.
                RecipeStateManager.Editability verdict =
                        RecipeStateManager.INSTANCE.editability(player.level().getServer(), id);
                if (verdict.refusal() != null) {
                    player.sendSystemMessage(Component.translatable(verdict.refusal(), id.toString()));
                    return;
                }
                RecipeStateManager.INSTANCE.disable(player.level().getServer(), id);
            }));
        });
        SceRawNetwork.toServer(ENABLE, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            context.queue(() -> ifAllowed(context.getPlayer(), player ->
                    RecipeStateManager.INSTANCE.enable(player.level().getServer(), id)));
        });
        SceRawNetwork.toServer(DELETE, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            context.queue(() -> ifAllowed(context.getPlayer(), player ->
                    RecipeStateManager.INSTANCE.deleteGenerated(player.level().getServer(), id)));
        });
        SceRawNetwork.toServer(REQUEST_JSON, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            context.queue(() -> handleRequestJson(context.getPlayer(), id));
        });
        SceRawNetwork.toServer(REQUEST_RECIPES, (buf, context) -> {
            Identifier itemId = buf.readIdentifier();
            context.queue(() -> handleRequestRecipes(context.getPlayer(), itemId));
        });
        SceRawNetwork.toServer(REQUEST_RECIPE_IDS, (buf, context) -> {
            long known = buf.readLong();
            context.queue(() -> handleRequestRecipeIds(context.getPlayer(), known));
        });
        SceRawNetwork.toServer(OPEN_EDITOR, (buf, context) -> {
            String idString = buf.readUtf();
            int mode = buf.readVarInt();
            String seed = buf.readUtf();
            context.queue(() -> handleOpenEditor(context.getPlayer(), idString, mode, seed));
        });
        SceRawNetwork.toServer(SET_ANVIL_RULES, (buf, context) -> {
            List<AnvilRule> rules = readAnvilRules(buf);
            context.queue(() -> ifAllowed(context.getPlayer(), player -> {
                AnvilRules.INSTANCE.set(rules);
                AnvilRules.INSTANCE.save();
                // Everyone, not just the sender: the rules decide what the anvil does for every player
                // on the server, and the recipe viewers read them on each client.
                syncToAll(player.level().getServer());
            }));
        });
        SceRawNetwork.toServer(SET_SLOT, (buf, context) -> {
            int slotId = buf.readVarInt();
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            context.queue(() -> handleSetSlot(context.getPlayer(), slotId, stack));
        });

        PlayerEvent.PLAYER_JOIN.register(SceNetworking::syncTo);
        PlayerEvent.PLAYER_QUIT.register(player -> lastPermission.remove(player.getUUID()));
        TickEvent.SERVER_POST.register(SceNetworking::trackPermissions);
    }

    private static void handleSave(Player sender, Identifier id, String json) {
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
        boolean ok = RecipeStateManager.INSTANCE.saveGenerated(player.level().getServer(), id, parsed);
        perf.stage("store it and apply it");
        player.sendSystemMessage(Component.translatable(
                ok ? "sce.msg.saved" : "sce.msg.rejected", id.toString()));
        sendSaveResult(player, id, ok);
        perf.stage("answer the player");
        perf.finish("{}, {} characters of json", ok ? "saved" : "rejected", json.length());
    }

    private static void sendSaveResult(ServerPlayer player, Identifier id, boolean ok) {
        RegistryFriendlyByteBuf buf = serverBuffer(player);
        buf.writeIdentifier(id);
        buf.writeBoolean(ok);
        SceRawNetwork.sendToPlayer(player, SAVE_RESULT, buf);
    }

    private static void handleRequestJson(Player sender, Identifier id) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        long started = ScePerf.now();
        JsonObject json = RecipeStateManager.INSTANCE.editorJson(id);
        RegistryFriendlyByteBuf buf = serverBuffer(player);
        buf.writeIdentifier(id);
        buf.writeUtf(json == null ? "" : json.toString(), MAX_JSON);
        SceRawNetwork.sendToPlayer(player, RECIPE_JSON, buf);
        ScePerf.since("answer one request for a recipe's json", started);
    }

    /**
     * Answers "which recipes produce this item". The search lives here rather than on the client for two
     * reasons: the server is the only side that can see every output a recipe declares, including the ones
     * that are not its main result, and from 1.21.11 on the client is not sent the recipes at all.
     */

    private static void handleRequestRecipes(Player sender, Identifier itemId) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        List<Identifier> recipes = List.of();
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item != null) {
            recipes = RecipeOutputIndex.INSTANCE.recipesProducing(player.level().getServer(), item);
        }
        RegistryFriendlyByteBuf buf = serverBuffer(player);
        buf.writeIdentifier(itemId);
        buf.writeVarInt(recipes.size());
        for (Identifier id : recipes) {
            buf.writeIdentifier(id);
        }
        SceRawNetwork.sendToPlayer(player, RECIPES_FOR, buf);
    }

    /**
     * Sends the recipe ids the editor's id field completes against.
     *
     * <p>On the older versions the field read them out of the client's own recipe manager. From 1.21.11
     * the client is not sent the recipes at all, so the ids have to be asked for — and ids are the whole
     * of what that field ever needed, which is all that travels here.
     *
     * <p>The client says which version of the list it already holds. If that is still the live one it is
     * told so and nothing else is sent, so opening the editor a second time costs a few bytes rather than
     * the whole list again. Otherwise the ids go over in batches, because a heavily modded server has
     * tens of thousands of them and one payload cannot carry them all.
     */
    private static void handleRequestRecipeIds(Player sender, long known) {
        if (!(sender instanceof ServerPlayer player) || !mayEdit(player)) {
            return;
        }
        long started = ScePerf.now();
        long epoch = RecipeStateManager.INSTANCE.recipeEpoch();
        if (known == epoch) {
            RegistryFriendlyByteBuf buf = serverBuffer(player);
            buf.writeLong(epoch);
            buf.writeBoolean(true);
            SceRawNetwork.sendToPlayer(player, RECIPE_IDS, buf);
            ScePerf.since("tell a client its recipe ids are still current", started);
            return;
        }
        List<Identifier> ids = RecipeStateManager.INSTANCE.liveRecipeIds(player.level().getServer());
        int sent = 0;
        // At least one packet always goes out, even with no recipes at all: the client is waiting for an
        // answer, and "none" is an answer.
        do {
            int to = Math.min(sent + ID_BATCH, ids.size());
            RegistryFriendlyByteBuf buf = serverBuffer(player);
            buf.writeLong(epoch);
            buf.writeBoolean(false);
            buf.writeBoolean(sent == 0);
            buf.writeBoolean(to == ids.size());
            buf.writeVarInt(to - sent);
            for (int i = sent; i < to; i++) {
                buf.writeIdentifier(ids.get(i));
            }
            SceRawNetwork.sendToPlayer(player, RECIPE_IDS, buf);
            sent = to;
        } while (sent < ids.size());
        SceDebug.log(SceDebug.Category.NETWORK, "Sent {} recipe ids for the id field", ids.size());
        ScePerf.since("send the recipe ids for the id field", started);
    }

    /**
     * A shapeless recipe with nothing in it that makes the given item, as JSON.
     *
     * <p>Built through the ordinary writer rather than by hand so it comes out in whatever shape this
     * version of the game reads, and so the editor opens it down the same path as any other recipe.
     */
    private static String blankRecipeFor(Identifier id, String itemId) {
        Identifier item = Identifier.tryParse(itemId);
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
        Identifier editId = idString.isEmpty() ? null : Identifier.tryParse(idString);
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
                    RecipeStateManager.INSTANCE.editability(player.level().getServer(), editId);
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
                if (!RecipeModes.available(mode)) {
                    // The recipe's type belongs to a mod that is not installed here. Falling back to the
                    // nearest typed editor would save it as something else and lose the fields that made
                    // it that mod's recipe, so the raw JSON editor is the only honest answer.
                    sendOpenRaw(player, editId, editJson);
                    perf.finish("its type needs a mod that is not here, sent the raw json one");
                    return;
                }
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
    public static void sendOpenSequence(ServerPlayer player, Identifier id, String json) {
        RegistryFriendlyByteBuf buf = serverBuffer(player);
        buf.writeIdentifier(id == null ? Identifier.parse("sce:new_recipe") : id);
        buf.writeUtf(json, MAX_JSON);
        SceRawNetwork.sendToPlayer(player, OPEN_SEQUENCE, buf);
    }

    public static void sendOpenRaw(ServerPlayer player, Identifier id, String json) {
        RegistryFriendlyByteBuf buf = serverBuffer(player);
        buf.writeIdentifier(id);
        buf.writeUtf(json, MAX_JSON);
        SceRawNetwork.sendToPlayer(player, OPEN_RAW, buf);
    }

    private record EditorMenuProvider(@Nullable Identifier editId, String editJson, int mode) implements ExtendedMenuProvider {
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

    private static void ifAllowed(Player sender, java.util.function.Consumer<ServerPlayer> action) {
        if (sender instanceof ServerPlayer player && mayEdit(player)) {
            action.accept(player);
        } else {
            deny(sender);
        }
    }

    private static void deny(Player sender) {
        if (sender instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable("sce.msg.no_permission"));
        }
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
        // Permission levels became named permissions; two used to mean the game-master tier.
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                && (player.isCreative() || player.isSpectator());
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

    /**
     * The anvil rules on the wire: two strings and a flag each.
     *
     * <p>Written as the text the file holds rather than as resolved items, because that is what a rule
     * is - a rule may name a tag no datapack has defined yet, and turning it into items here would throw
     * that away on the way to the one screen that has to show it back.
     */
    public static void writeAnvilRules(FriendlyByteBuf buf, List<AnvilRule> rules) {
        buf.writeVarInt(rules.size());
        for (AnvilRule rule : rules) {
            buf.writeUtf(rule.target(), 256);
            buf.writeUtf(rule.material(), 256);
            buf.writeBoolean(rule.mode() == AnvilRule.Mode.REPLACE);
        }
    }

    public static List<AnvilRule> readAnvilRules(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<AnvilRule> rules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String target = buf.readUtf(256);
            String material = buf.readUtf(256);
            rules.add(new AnvilRule(target, material,
                    buf.readBoolean() ? AnvilRule.Mode.REPLACE : AnvilRule.Mode.ADD));
        }
        return rules;
    }

    /** Sends the rules the screen is showing back to the server, which stores them and tells everyone. */
    public static void sendAnvilRules(List<AnvilRule> rules) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        writeAnvilRules(buf, rules);
        SceRawNetwork.sendToServer(SET_ANVIL_RULES, buf);
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
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        long started = ScePerf.now();
        RecipeStateManager manager = RecipeStateManager.INSTANCE;
        RegistryFriendlyByteBuf buf = serverBuffer(player);

        buf.writeBoolean(mayEdit(player)); // whether this player may open the editor at all
        buf.writeVarInt(SceDebug.mask());  // so the client logs the same categories the server does

        Map<Identifier, JsonObject> disabled = manager.state().disabled();
        buf.writeVarInt(disabled.size());
        for (Map.Entry<Identifier, JsonObject> entry : disabled.entrySet()) {
            ItemStack display = disabledDisplay(server, entry.getKey(), entry.getValue());
            buf.writeIdentifier(entry.getKey());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, display);
            buf.writeBoolean(display.isEmpty()); // unresolved
            buf.writeBoolean(false);             // (unused for datapack recipes)
        }

        var generated = manager.state().generated().keySet();
        buf.writeVarInt(generated.size());
        for (Identifier id : generated) {
            buf.writeIdentifier(id);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, manager.generatedResultOf(server, id));
            buf.writeBoolean(manager.wasBaseRecipe(id));                // true = edit of an existing recipe
            buf.writeBoolean(manager.state().isGeneratedDisabled(id));  // toggled off
        }

        writeAnvilRules(buf, AnvilRules.INSTANCE.rules());

        SceRawNetwork.sendToPlayer(player, SYNC, buf);
        // One player at a time: the interesting number is what this adds up to across a full server.
        ScePerf.since("build and send one player's editor state", started);
    }

    private static ItemStack disabledDisplay(MinecraftServer server, Identifier id, JsonObject snapshot) {
        if (snapshot == null) {
            return ItemStack.EMPTY;
        }
        long started = ScePerf.now();
        try {
            RegistryAccess access = server.registryAccess();
            Recipe<?> recipe = Recipe.CODEC.parse(access.createSerializationContext(JsonOps.INSTANCE), snapshot)
                    .getOrThrow(com.google.gson.JsonParseException::new);
            ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
            for (RecipeDisplay display : recipe.display()) {
                ItemStack stack = display.result().resolveForFirstStack(context);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        } finally {
            ScePerf.since("work out one disabled recipe's icon", started);
        }
    }

    // ------------------------------------------------------------------ client -> server send helpers

    public static void sendSave(Identifier id, String json) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeIdentifier(id);
        buf.writeUtf(json, MAX_JSON);
        SceRawNetwork.sendToServer(SAVE, buf);
    }

    public static void sendSimple(Identifier channel, Identifier recipeId) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeIdentifier(recipeId);
        SceRawNetwork.sendToServer(channel, buf);
    }

    /** Asks the server which recipes produce an item. The answer comes back on {@link #RECIPES_FOR}. */
    public static void sendRequestRecipes(Identifier itemId) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeIdentifier(itemId);
        SceRawNetwork.sendToServer(REQUEST_RECIPES, buf);
    }

    /**
     * Asks for the recipe ids the id field completes against. {@code known} is the version of the list
     * this client already holds, so a list that has not changed does not have to be sent again; zero when
     * it holds none. The answer comes back on {@link #RECIPE_IDS}.
     */
    public static void sendRequestRecipeIds(long known) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeLong(known);
        SceRawNetwork.sendToServer(REQUEST_RECIPE_IDS, buf);
    }

    /** Asks the server to open the editor menu; empty id means a fresh recipe, mode -1 means "derive from recipe". */
    public static void sendOpenEditor(String idString, int mode) {
        sendOpenEditor(idString, mode, null);
    }

    /** As above, plus the item a brand-new recipe should start out making. */
    public static void sendOpenEditor(String idString, int mode, Identifier seedResult) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeUtf(idString);
        buf.writeVarInt(mode);
        buf.writeUtf(seedResult == null ? "" : seedResult.toString());
        SceRawNetwork.sendToServer(OPEN_EDITOR, buf);
    }

    /** Places a real item into one of the editor's recipe slots (used by JEI/EMI drag). */
    public static void sendSetSlot(int slotId, ItemStack stack) {
        RegistryFriendlyByteBuf buf = clientBuffer();
        buf.writeVarInt(slotId);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        SceRawNetwork.sendToServer(SET_SLOT, buf);
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
