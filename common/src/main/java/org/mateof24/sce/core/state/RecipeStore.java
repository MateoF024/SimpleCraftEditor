package org.mateof24.sce.core.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.sce.SimpleCraftEditor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Global (per-instance) persistence for {@link RecipeState}, at {@code config/sce/recipes.json}.
 * Global rather than per-world because the audience is modpack authors: the ruleset is authored once
 * and ships with the pack, independent of which worlds a player creates.
 */
public final class RecipeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RecipeStore() {
    }

    private static Path file() {
        return Platform.getConfigFolder().resolve(SimpleCraftEditor.MOD_ID).resolve("recipes.json");
    }

    public static RecipeState load() {
        RecipeState state = new RecipeState();
        Path path = file();
        if (!Files.exists(path)) {
            return state;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return state;
            }
            readObject(root, "disabled", (id, el) ->
                    state.disabled().put(id, el.isJsonObject() ? el.getAsJsonObject() : null));
            readObject(root, "generated", (id, el) -> {
                if (el.isJsonObject()) {
                    state.generated().put(id, el.getAsJsonObject());
                }
            });
            if (root.has("hidden") && root.get("hidden").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("hidden")) {
                    ResourceLocation id = tryParse(el.getAsString());
                    if (id != null) {
                        state.hidden().add(id);
                    }
                }
            }
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.error("Failed to read recipe state from {}", path, e);
        }
        return state;
    }

    public static void save(RecipeState state) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();

            JsonObject disabled = new JsonObject();
            state.disabled().forEach((id, json) -> disabled.add(id.toString(), json == null ? JsonNull.INSTANCE : json));
            root.add("disabled", disabled);

            JsonObject generated = new JsonObject();
            state.generated().forEach((id, json) -> generated.add(id.toString(), json));
            root.add("generated", generated);

            JsonArray hidden = new JsonArray();
            state.hidden().forEach(id -> hidden.add(id.toString()));
            root.add("hidden", hidden);

            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            SimpleCraftEditor.LOGGER.error("Failed to write recipe state to {}", path, e);
        }
    }

    private static void readObject(JsonObject root, String key, BiConsumer<ResourceLocation, JsonElement> consumer) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(key).entrySet()) {
            ResourceLocation id = tryParse(entry.getKey());
            if (id != null) {
                consumer.accept(id, entry.getValue());
            }
        }
    }

    private static ResourceLocation tryParse(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            SimpleCraftEditor.LOGGER.warn("Ignoring invalid recipe id in recipe state config: '{}'", raw);
        }
        return id;
    }
}
