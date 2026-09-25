package org.mateof24.sce.core.anvil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.SceDebug;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the anvil accepts as a repair material, and the file it is kept in.
 *
 * <p>A subsystem of its own rather than a kind of recipe. Nothing about repairing lives in the
 * {@code RecipeManager}: what decides whether one item mends another is a single method on the item,
 * answered in Java from the tier and armour-material tables. So this has its own file —
 * {@code config/sce/anvil.json}, beside {@code recipes.json} and never touching it — its own place in
 * the sync packet, and its own screen.
 *
 * <p>One instance serves both sides. The server reads the file and sends the list to every client; the
 * client replaces its copy with what arrives. The anvil asks on whichever side it is running, and so do
 * the recipe viewers, which are client-only and need the same answer the anvil gives or they show the
 * player something that is not true.
 */
public final class AnvilRules {
    public static final AnvilRules INSTANCE = new AnvilRules();

    /**
     * The file's format number, written from the first version so a later change has something to read.
     * {@code recipes.json} learned this late and had to guess; this one does not have to.
     */
    public static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * What the rules say about one pairing.
     *
     * <p>{@link #UNKNOWN} is the important one: no rule mentions this item, so the answer is vanilla's
     * and the hook steps out of the way. Rules are a small list of exceptions, not a replacement for the
     * game's own table.
     */
    public enum Verdict {
        YES, NO, UNKNOWN
    }

    private List<AnvilRule> rules = List.of();

    private AnvilRules() {
    }

    public List<AnvilRule> rules() {
        return rules;
    }

    /** Replaces the whole list — from the file on the server, from the sync packet on a client. */
    public void set(List<AnvilRule> replacement) {
        List<AnvilRule> kept = new ArrayList<>(replacement.size());
        for (AnvilRule rule : replacement) {
            if (rule != null && rule.isComplete()) {
                kept.add(rule);
            }
        }
        rules = List.copyOf(kept);
    }

    /**
     * What the rules say about mending {@code toRepair} with {@code material}.
     *
     * <p>A material that any rule for this item allows is a yes, whatever the other rules say. Otherwise,
     * a rule that says <em>replace</em> means the game's own material no longer works and the answer is
     * no; if every rule for the item only <em>adds</em> to what worked before, the answer is vanilla's.
     */
    public Verdict verdict(ItemStack toRepair, ItemStack material) {
        if (rules.isEmpty() || toRepair.isEmpty() || material.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        boolean replaced = false;
        for (AnvilRule rule : rules) {
            if (!rule.matchesTarget(toRepair)) {
                continue;
            }
            if (rule.matchesMaterial(material)) {
                return Verdict.YES;
            }
            replaced |= rule.mode() == AnvilRule.Mode.REPLACE;
        }
        return replaced ? Verdict.NO : Verdict.UNKNOWN;
    }

    // ------------------------------------------------------------------ the file

    private static Path file() {
        return Platform.getConfigFolder().resolve(SimpleCraftEditor.MOD_ID).resolve("anvil.json");
    }

    /** Reads the file into this instance. A missing file is not a problem: it means no rules. */
    public void load() {
        Path path = file();
        if (!Files.exists(path)) {
            set(List.of());
            return;
        }
        List<AnvilRule> read = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("rules") && root.get("rules").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("rules")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    read.add(new AnvilRule(
                            string(object, "target"),
                            string(object, "material"),
                            AnvilRule.Mode.of(string(object, "mode"))));
                }
            }
        } catch (Exception e) {
            // A file that will not parse is left alone rather than overwritten: whoever hand-edited it
            // can still fix it, which they cannot do if the next save flattens it.
            SimpleCraftEditor.LOGGER.error("Failed to read the anvil rules from {}", path, e);
            return;
        }
        set(read);
        SceDebug.log(SceDebug.Category.EDIT, "Read {} anvil rule(s) from {}", rules.size(), path);
    }

    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format", FORMAT);
            JsonArray array = new JsonArray();
            for (AnvilRule rule : rules) {
                JsonObject object = new JsonObject();
                object.addProperty("target", rule.target());
                object.addProperty("material", rule.material());
                object.addProperty("mode", rule.mode().key());
                array.add(object);
            }
            root.add("rules", array);
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.error("Failed to write the anvil rules to {}", path, e);
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
