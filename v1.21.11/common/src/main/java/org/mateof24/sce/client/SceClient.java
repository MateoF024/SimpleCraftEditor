package org.mateof24.sce.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.architectury.registry.menu.MenuRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.screen.RawRecipeScreen;
import org.mateof24.sce.client.screen.RecipeEditorScreen;
import org.mateof24.sce.client.screen.RecipeManagerScreen;
import org.mateof24.sce.client.screen.SequencedAssemblyScreen;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.edit.RecipeModes;
import org.mateof24.sce.mixin.HoveredSlotAccessor;
import org.mateof24.sce.core.ScePerf;
import org.mateof24.sce.net.SceNetworking;
import org.mateof24.sce.registry.SceMenus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;

/** Client entrypoint: keybind, S2C receivers and the tick hook that opens the manager screen. */
@Environment(EnvType.CLIENT)
public final class SceClient {
    /** Key categories are registered objects now, and their label comes from the id they are given. */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, "editor"));
    private static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.sce.open_manager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    // Item under the cursor, supplied by the JEI and EMI integrations when they are present.
    private static final List<Supplier<ItemStack>> HOVERED_PROVIDERS = new ArrayList<>();

    // Which item the key is walking the recipes of, how far along it is, and the answer the server gave
    // for that item. Held so a second press steps forward instead of asking the same question again.
    private static Item cyclingItem;
    private static int cycleIndex;
    private static List<Identifier> cyclingRecipes = List.of();
    /** The item a request is out for, so a late answer for a different one can be recognised and dropped. */
    private static ItemStack pending = ItemStack.EMPTY;

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
    public static boolean tryLoadHoveredRecipe(KeyEvent event) {
        if (!OPEN_MANAGER.matches(event) || !ClientEditorState.canEdit() || typingSomewhere()) {
            return false;
        }
        long started = ScePerf.now();
        ItemStack hovered = hoveredItem();
        ScePerf.since("find the item under the cursor", started);
        if (hovered.isEmpty()) {
            return false;
        }
        // Held shift asks for the other thing entirely: not "show me what makes this" but "let me write
        // what makes this". Taken from the modifiers of this very press rather than from the keyboard's
        // state, so it is the shift that was down when the key went down.
        if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            openBlankFor(hovered);
            return true;
        }
        // Still the same item: the server already said what makes it, so step to the next one without
        // asking again. This is what makes holding the key down to walk a list feel immediate.
        if (hovered.getItem() == cyclingItem && !cyclingRecipes.isEmpty()) {
            cycleIndex = (cycleIndex + 1) % cyclingRecipes.size();
            openCycled();
            return true;
        }
        // A different item, so ask. Only the server can answer this: it is the side that can see every
        // output a recipe declares rather than just its main result — see RecipeOutputIndex.
        cyclingItem = null;
        cyclingRecipes = List.of();
        cycleIndex = 0;
        pending = hovered.copy();
        SceNetworking.sendRequestRecipes(BuiltInRegistries.ITEM.getKey(hovered.getItem()));
        return true;
    }

    /**
     * The server's answer to what makes an item. Ignored if the pointer has already moved on to something
     * else, so a slow answer can never open the recipe for an item that is no longer under the cursor.
     */
    private static void onRecipesFor(Identifier itemId, List<Identifier> recipes) {
        if (pending.isEmpty() || !BuiltInRegistries.ITEM.getKey(pending.getItem()).equals(itemId)) {
            return;
        }
        ItemStack target = pending;
        pending = ItemStack.EMPTY;
        Minecraft minecraft = Minecraft.getInstance();
        if (recipes.isEmpty()) {
            cyclingItem = null;
            if (minecraft.player != null) {
                // Say what can be done about it, in the key the player actually has bound.
                minecraft.player.displayClientMessage(Component.translatable(
                        "sce.msg.no_recipe", target.getHoverName(), blankRecipeKey()), false);
            }
            return;
        }
        cyclingItem = target.getItem();
        cyclingRecipes = recipes;
        cycleIndex = 0;
        openCycled();
    }

    /**
     * Shift and the open key: a new shapeless recipe that makes this item, ready to be filled in.
     *
     * <p>The long way round is to open the manager, press New Recipe and then find the item again. This
     * is the same thing in one press, which is worth having for the item that has no recipe yet — the
     * case where the plain key can only say "there is none".
     */
    private static void openBlankFor(ItemStack stack) {
        Identifier item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // Whatever the key was walking is over: this opens something that is not in that list.
        cyclingItem = null;
        cyclingRecipes = List.of();
        cycleIndex = 0;
        pending = ItemStack.EMPTY;
        SceDebug.dump(SceDebug.Category.CLIENT, () -> "shift+K over " + item + ": new recipe");
        SceNetworking.sendOpenEditor(newRecipeId(item), RecipeModes.shapelessMode(), item);
    }

    /**
     * The id a new recipe starts with: {@code sce:copper_ingot} for a vanilla item, and
     * {@code sce:create/andesite_alloy} for anyone else's.
     *
     * <p>The namespace is kept for modded items because two mods naming the same thing is ordinary, and
     * without it the second recipe written would quietly land on the first. It is only a starting point;
     * the id box is right there.
     */
    private static String newRecipeId(Identifier item) {
        String path = "minecraft".equals(item.getNamespace())
                ? item.getPath() : item.getNamespace() + "/" + item.getPath();
        return SimpleCraftEditor.MOD_ID + ":" + path;
    }

    /** The open key as it is bound right now, with the shift that always goes with it. */
    private static Component blankRecipeKey() {
        return Component.translatable("sce.key.with_shift", OPEN_MANAGER.getTranslatedKeyMessage());
    }

    /** Opens whichever recipe the cycle is on and names it, keeping the pointer where it is. */
    private static void openCycled() {
        Identifier picked = cyclingRecipes.get(cycleIndex);
        SceDebug.dump(SceDebug.Category.CLIENT, () -> "K over " + cyclingItem + ": "
                + cyclingRecipes.size() + " recipe(s) " + cyclingRecipes + ", loading " + picked);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "sce.msg.recipe_cycle", cycleIndex + 1, cyclingRecipes.size(), picked.toString()), false);
        }
        // Keep the pointer where it is: stepping through recipes re-opens the editor each time, and the
        // menu transition would otherwise recenter it between presses.
        RecipeEditorScreen.rememberCursor();
        SceNetworking.sendOpenEditor(picked.toString(), -1);
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
        // Always last, never registered as one of the providers above: a recipe viewer's own list has to
        // win when the pointer is over it, and this is the answer for everywhere else.
        return hoveredInOpenScreen();
    }

    /**
     * The item under the pointer in whatever screen is open, for when no recipe viewer answered.
     *
     * <p>Covers the player's inventory, a chest, the creative menu, this mod's own editor and any other
     * mod's container screen, because all of them are built on slots. That is what lets the key work
     * with no recipe viewer installed at all, which used to be the one case where a recipe could only be
     * reached by typing its id by hand.
     */
    private static ItemStack hoveredInOpenScreen() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = ((HoveredSlotAccessor) container).sce$hoveredSlot();
            if (slot != null && slot.hasItem()) {
                return slot.getItem();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Whether the key should be left alone because something is being typed into.
     *
     * <p>A screen delivers a key press and a typed character as two separate events, so refusing the
     * first does not stop the second: without this, pressing "k" in a text box would type the letter
     * <em>and</em> jump to a recipe. It never mattered while the key only answered over a recipe
     * viewer's list; it does now that it answers over any slot.
     */
    private static boolean typingSomewhere() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            return false;
        }
        GuiEventListener focused = screen.getFocused();
        return focused instanceof EditBox || focused instanceof MultiLineEditBox;
    }

    private static boolean keysRegistered;

    /**
     * Registers the editor key. Separate from {@link #init()} because a loader collects key mappings at a
     * fixed moment during startup: register after it and the key is accepted, listed in the controls
     * screen, and never fires. Whoever wires up a loader calls this early enough to be counted, and
     * calling it twice is harmless.
     */
    public static void registerKeyMappings() {
        if (keysRegistered) {
            return;
        }
        keysRegistered = true;
        registerKeyMappings();
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


    private static void registerReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.SYNC, (buf, context) -> {
            long started = ScePerf.now();
            boolean canEdit = buf.readBoolean();
            int debugMask = buf.readVarInt();
            List<ClientEditorState.Entry> disabled = readEntries(buf);
            List<ClientEditorState.Entry> generated = readEntries(buf);
            ScePerf.since("read the editor state the server sent", started);
            context.queue(() -> {
                // In singleplayer this is the same JVM as the server, so the mask is already set; on a
                // dedicated server this is how the client learns which categories to log under.
                SceDebug.setMask(debugMask);
                // The recipe set has just changed, so what the server told us makes an item may no longer
                // be true. Forget it rather than step through a list that is out of date.
                cyclingItem = null;
                cyclingRecipes = List.of();
                ClientEditorState.setCanEdit(canEdit);
                ClientEditorState.setDisabled(disabled);
                ClientEditorState.setGenerated(generated);
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.RECIPES_FOR, (buf, context) -> {
            Identifier itemId = buf.readIdentifier();
            int count = buf.readVarInt();
            List<Identifier> recipes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                recipes.add(buf.readIdentifier());
            }
            context.queue(() -> onRecipesFor(itemId, recipes));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.RECIPE_JSON, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            String raw = buf.readUtf(1024 * 1024);
            JsonObject json = raw.isEmpty() ? null : JsonParser.parseString(raw).getAsJsonObject();
            context.queue(() -> ClientEditorState.onJsonResponse(id, json));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.OPEN_RAW, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            String json = buf.readUtf(1024 * 1024);
            context.queue(() -> Minecraft.getInstance().setScreen(new RawRecipeScreen(id, json)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.OPEN_SEQUENCE, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            String json = buf.readUtf(1024 * 1024);
            context.queue(() -> Minecraft.getInstance().setScreen(new SequencedAssemblyScreen(id, json)));
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SceNetworking.SAVE_RESULT, (buf, context) -> {
            Identifier id = buf.readIdentifier();
            boolean ok = buf.readBoolean();
            context.queue(() -> {
                if (Minecraft.getInstance().screen instanceof RecipeEditorScreen editor) {
                    editor.onSaveResult(id, ok);
                } else if (Minecraft.getInstance().screen instanceof SequencedAssemblyScreen sequence) {
                    sequence.onSaveResult(id, ok);
                } else if (Minecraft.getInstance().screen instanceof RawRecipeScreen raw) {
                    // The raw editor saves through the same channel, so the server always answered it;
                    // until now nobody was listening and it could only ever say "sent".
                    raw.onSaveResult(id, ok);
                }
            });
        });
    }


    private static List<ClientEditorState.Entry> readEntries(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ClientEditorState.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier id = buf.readIdentifier();
            ItemStack display = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            boolean flag = buf.readBoolean();
            boolean disabled = buf.readBoolean();
            entries.add(new ClientEditorState.Entry(id, display, flag, disabled));
        }
        return entries;
    }
}
