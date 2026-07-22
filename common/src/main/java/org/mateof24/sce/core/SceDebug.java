package org.mateof24.sce.core;

import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import org.mateof24.sce.SimpleCraftEditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The mod's debug switch: intensive logging that can be turned on to trace exactly what happens to a
 * recipe, from a key press to the result, without recompiling.
 *
 * <p>Logging is grouped into {@link Category categories} so a session can be narrowed to one concern.
 * The cost is nothing when a category is off: the guard is a single field read, and any expensive dump
 * is written as a {@link Supplier} that only runs once the category is confirmed on.
 *
 * <p>Everything is written through the mod's own {@code LOGGER} at INFO, prefixed {@code [SCE-DBG/<cat>]},
 * so it lands in a normal {@code latest.log} — a modpack's log level is almost never set to DEBUG, so
 * {@code LOGGER.debug} would be swallowed.
 *
 * <p>Three ways to turn it on, because the failure this exists to diagnose happens during world load,
 * before anyone can type a command: the {@code sce.debug} system property (read at startup), a persisted
 * flag file that survives restarts (written by the command), and the command itself at runtime. Read
 * {@link #loadStartup()} once, as early in init as possible, so startup instrumentation is already live.
 *
 * <p>Server-authoritative. In singleplayer the integrated server shares this JVM with the client, so
 * setting it once covers both; on a dedicated server the flag is pushed to clients in the sync packet.
 */
public final class SceDebug {
    /** What a debug line is about, so a session can be scoped to one of them. */
    public enum Category {
        /** Datapack reload, recipe capture, base snapshot — the heart of the load-a-recipe bug. */
        RELOAD,
        /** Opening the editor, resolving a recipe's JSON, parsing, saving. */
        EDIT,
        /** Every packet in and out. */
        NETWORK,
        /** Soft-dependency integrations (KubeJS, CraftTweaker) being detected or used. */
        COMPAT,
        /** Permission changes as they are noticed and pushed. */
        PERMISSION,
        /** Client-side: the recipe key, load requests, field completion. */
        CLIENT
    }

    private static final String PROPERTY = "sce.debug";
    private static final String FLAG_FILE = "debug.flag";

    private static final Set<Category> active = EnumSet.noneOf(Category.class);

    private SceDebug() {
    }

    // ------------------------------------------------------------------ query

    public static boolean isOn(Category category) {
        return active.contains(category);
    }

    public static boolean anyOn() {
        return !active.isEmpty();
    }

    /** The active set as a bitmask, for sending to clients in one int. */
    public static int mask() {
        int mask = 0;
        for (Category category : active) {
            mask |= 1 << category.ordinal();
        }
        return mask;
    }

    // ------------------------------------------------------------------ logging

    /** Logs under {@code category} if it is on. {@code message} uses SLF4J {@code {}} placeholders. */
    public static void log(Category category, String message, Object... args) {
        if (isOn(category)) {
            SimpleCraftEditor.LOGGER.info("[SCE-DBG/" + category.name() + "] " + message, args);
        }
    }

    /** Like {@link #log}, but the message is only built when the category is on — for costly dumps. */
    public static void dump(Category category, Supplier<String> message) {
        if (isOn(category)) {
            SimpleCraftEditor.LOGGER.info("[SCE-DBG/{}] {}", category.name(), message.get());
        }
    }

    // ------------------------------------------------------------------ mutation

    /** Turns a category on or off at runtime (from the command). Does not persist on its own. */
    public static void set(Category category, boolean on) {
        if (on) {
            active.add(category);
        } else {
            active.remove(category);
        }
    }

    public static void setAll(boolean on) {
        active.clear();
        if (on) {
            active.addAll(EnumSet.allOf(Category.class));
        }
    }

    /** Replaces the whole active set from a bitmask (used on the client when the server sends one). */
    public static void setMask(int mask) {
        active.clear();
        for (Category category : Category.values()) {
            if ((mask & (1 << category.ordinal())) != 0) {
                active.add(category);
            }
        }
    }

    /** A short human-readable summary of what is on, for the status command. */
    public static String describe() {
        return active.isEmpty() ? "off" : active.toString();
    }

    // ------------------------------------------------------------------ startup + persistence

    /**
     * Reads the initial state at startup: the persisted flag first, then the {@code sce.debug} system
     * property on top (so {@code -Dsce.debug=...} always wins for a one-off diagnosis). Call once, early.
     */
    public static void loadStartup() {
        applySpec(readFlagFile(), false);
        String property = System.getProperty(PROPERTY);
        if (property != null) {
            applySpec(property, true);
        }
        if (anyOn()) {
            SimpleCraftEditor.LOGGER.info("[SCE-DBG] Debug logging active at startup: {}", describe());
        }
    }

    /** Persists the current active set so the next startup begins with it. Off clears the file. */
    public static void persist() {
        Path path = flagPath();
        try {
            if (!anyOn()) {
                Files.deleteIfExists(path);
                return;
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, specString());
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Could not persist debug flag to {}: {}", path, e.toString());
        }
    }

    /**
     * Applies a spec such as {@code true}, {@code false}, {@code all}, or a comma-separated category list
     * ({@code reload,edit}). {@code replace} clears first; otherwise it adds to what is already on.
     */
    public static void applySpec(String spec, boolean replace) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        if (replace) {
            active.clear();
        }
        for (String token : spec.split(",")) {
            String key = token.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals("true") || key.equals("all") || key.equals("on")) {
                setAll(true);
            } else if (key.equals("false") || key.equals("none") || key.equals("off")) {
                active.clear();
            } else {
                for (Category category : Category.values()) {
                    if (category.name().toLowerCase(Locale.ROOT).equals(key)) {
                        active.add(category);
                    }
                }
            }
        }
    }

    private static String specString() {
        StringBuilder sb = new StringBuilder();
        for (Category category : active) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(category.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String readFlagFile() {
        Path path = flagPath();
        try {
            return Files.exists(path) ? Files.readString(path).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path flagPath() {
        return Platform.getConfigFolder().resolve(SimpleCraftEditor.MOD_ID).resolve(FLAG_FILE);
    }

    // ------------------------------------------------------------------ startup dump

    /**
     * One-shot report of the mods that matter to recipe handling, with versions. This is the line that
     * would have named the recipe-load bug's cause immediately: a heavy pack with KubeJS is exactly the
     * case the editor's capture path did not survive.
     */
    public static void reportEnvironment() {
        if (!isOn(Category.RELOAD)) {
            return;
        }
        StringBuilder sb = new StringBuilder("Recipe-relevant mods present:");
        for (String id : new String[]{"kubejs", "crafttweaker", "create", "jei", "emi", "architectury"}) {
            if (Platform.isModLoaded(id)) {
                Mod mod = Platform.getMod(id);
                sb.append("\n  - ").append(id).append(' ').append(mod != null ? mod.getVersion() : "?");
            }
        }
        SimpleCraftEditor.LOGGER.info("[SCE-DBG/RELOAD] {}", sb);
    }
}
