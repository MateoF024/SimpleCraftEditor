package org.mateof24.sce.core.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.minecraft.resources.Identifier;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.ScePerf;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        ScePerf.Run perf = ScePerf.start("read our recipe file");
        RecipeState state = new RecipeState();
        Path path = file();
        if (!Files.exists(path)) {
            perf.finish("no file yet");
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
            readArray(root, "hidden", id -> state.hidden().add(id));
            readArray(root, "disabled_generated", id -> state.disabledGenerated().add(id));
            // The file may have been written by this mod on another version of the game, whose recipe
            // JSON is not this one's. Put it into this version's shape before anything tries to read it,
            // and write it back so the work is done once rather than on every start.
            int written = root.has("data_version") && root.get("data_version").isJsonPrimitive()
                    ? root.get("data_version").getAsInt() : 0;
            int updated = RecipeMigration.apply(state, written);
            if (updated > 0 || written != RecipeMigration.currentDataVersion()) {
                if (updated > 0) {
                    SimpleCraftEditor.LOGGER.info(
                            "Updated {} stored recipe(s) written by another version of the game", updated);
                }
                if (updated > 0) {
                    backup(path);
                }
                save(state);
            }
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.error("Failed to read recipe state from {}", path, e);
        }
        perf.finish("{} disabled, {} of ours", state.disabled().size(), state.generated().size());
        return state;
    }

    /**
     * The file as it was, kept beside it under {@code recipes.json.bak}.
     *
     * <p>Only before a rewrite this mod did on its own, which is the one write the player did not ask
     * for. It is their authored work and there is exactly one copy of it; a conversion that goes wrong
     * without one would be the worst thing this mod could do. One file rather than one per start, so it
     * is the version that was carried over and not a pile.
     */
    private static void backup(Path path) {
        try {
            Files.copy(path, path.resolveSibling("recipes.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Worth saying, not worth stopping for: the recipes in memory are already correct.
            SimpleCraftEditor.LOGGER.warn("Could not keep a copy of the recipe file before updating it", e);
        }
    }

    public static void save(RecipeState state) {
        ScePerf.Run perf = ScePerf.start("write our recipe file");
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            // What wrote it, so the next version knows exactly how far to carry the item data inside.
            root.addProperty("data_version", RecipeMigration.currentDataVersion());

            JsonObject disabled = new JsonObject();
            state.disabled().forEach((id, json) -> disabled.add(id.toString(), json == null ? JsonNull.INSTANCE : json));
            root.add("disabled", disabled);

            JsonObject generated = new JsonObject();
            state.generated().forEach((id, json) -> generated.add(id.toString(), json));
            root.add("generated", generated);

            JsonArray hidden = new JsonArray();
            state.hidden().forEach(id -> hidden.add(id.toString()));
            root.add("hidden", hidden);

            JsonArray disabledGenerated = new JsonArray();
            state.disabledGenerated().forEach(id -> disabledGenerated.add(id.toString()));
            root.add("disabled_generated", disabledGenerated);

            perf.stage("build the json");
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(root, writer);
            }
            perf.stage("write to disk");
            perf.finish("{} disabled, {} of ours, {} bytes",
                    state.disabled().size(), state.generated().size(), Files.size(path));
        } catch (IOException e) {
            SimpleCraftEditor.LOGGER.error("Failed to write recipe state to {}", path, e);
            perf.finish("failed");
        }
    }

    private static void readObject(JsonObject root, String key, BiConsumer<Identifier, JsonElement> consumer) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(key).entrySet()) {
            Identifier id = tryParse(entry.getKey());
            if (id != null) {
                consumer.accept(id, entry.getValue());
            }
        }
    }

    private static void readArray(JsonObject root, String key, java.util.function.Consumer<Identifier> consumer) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray(key)) {
            Identifier id = tryParse(el.getAsString());
            if (id != null) {
                consumer.accept(id);
            }
        }
    }

    private static Identifier tryParse(String raw) {
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            SimpleCraftEditor.LOGGER.warn("Ignoring invalid recipe id in recipe state config: '{}'", raw);
        }
        return id;
    }
}
