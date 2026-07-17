package org.mateof24.sce.client;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
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
    /** {@code flag} means unresolved for disabled recipes, and "edit of an existing recipe" for generated ones. */
    public record Entry(ResourceLocation id, ItemStack display, boolean flag) {
    }

    private static final List<Entry> DISABLED = new ArrayList<>();
    private static final List<Entry> GENERATED = new ArrayList<>();
    private static final Map<ResourceLocation, Consumer<JsonObject>> PENDING = new HashMap<>();

    private ClientEditorState() {
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
    public static void requestJson(ResourceLocation id, Consumer<JsonObject> callback) {
        PENDING.put(id, callback);
        SceNetworking.sendSimple(SceNetworking.REQUEST_JSON, id);
    }

    public static void onJsonResponse(ResourceLocation id, JsonObject json) {
        Consumer<JsonObject> callback = PENDING.remove(id);
        if (callback != null) {
            callback.accept(json);
        }
    }
}
