package org.mateof24.sce.client;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Client-side mirror of the server's disabled/generated sets, plus pending recipe-JSON requests. */
@Environment(EnvType.CLIENT)
public final class ClientEditorState {
    /**
     * {@code flag} means unresolved for disabled recipes and "edit of an existing recipe" for generated ones.
     * {@code disabled} is only meaningful for generated recipes: whether they are toggled off.
     */
    public record Entry(Identifier id, ItemStack display, boolean flag, boolean disabled) {
    }

    private static final List<Entry> DISABLED = new ArrayList<>();
    private static final List<Entry> GENERATED = new ArrayList<>();
    private static final Map<Identifier, Consumer<JsonObject>> PENDING = new HashMap<>();

    // Whether this player may edit recipes, decided by the server (operator, or singleplayer with cheats).
    private static boolean canEdit;

    private ClientEditorState() {
    }

    public static boolean canEdit() {
        return canEdit;
    }

    public static void setCanEdit(boolean value) {
        canEdit = value;
    }

    public static List<Entry> disabled() {
        return DISABLED;
    }

    public static List<Entry> generated() {
        return GENERATED;
    }

    public static void setDisabled(List<Entry> entries) {
        DISABLED.clear();
        DISABLED.addAll(entries);
    }

    public static void setGenerated(List<Entry> entries) {
        GENERATED.clear();
        GENERATED.addAll(entries);
    }

    /** Ask the server for a recipe's original JSON; {@code callback} receives it (or null) when it arrives. */
    public static void requestJson(Identifier id, Consumer<JsonObject> callback) {
        PENDING.put(id, callback);
        SceNetworking.sendSimple(SceNetworking.REQUEST_JSON, id);
    }

    public static void onJsonResponse(Identifier id, JsonObject json) {
        Consumer<JsonObject> callback = PENDING.remove(id);
        if (callback != null) {
            callback.accept(json);
        }
    }
}
