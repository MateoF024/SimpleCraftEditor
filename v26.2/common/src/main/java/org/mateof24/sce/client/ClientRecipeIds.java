package org.mateof24.sce.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.net.SceNetworking;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe ids the editor's id field completes against.
 *
 * <p>On 1.20.1 and 1.21.1 the field read them straight out of the client's own recipe manager. From
 * 1.21.11 the client is not sent the recipes at all — it keeps display data for its recipe book and no
 * list of names — so the ids are asked for and kept here instead. The field behaves the same either way;
 * only where the list comes from is different.
 *
 * <p>The list is asked for when a screen with such a field opens, not held from login: a player who
 * never edits a recipe never pays for it. What comes back is remembered along with the version of the
 * recipe set it came from, so opening the editor again asks the same question and is usually told in a
 * few bytes that nothing has changed.
 */
@Environment(EnvType.CLIENT)
public final class ClientRecipeIds {
    private static final List<String> IDS = new ArrayList<>();
    /** Batches as they arrive; only swapped into {@link #IDS} once the last one is in. */
    private static final List<String> INCOMING = new ArrayList<>();
    /** Which version of the server's recipe set {@link #IDS} came from; zero means we hold nothing. */
    private static long epoch;

    private ClientRecipeIds() {
    }

    /** Every recipe id the server had when it last answered. Empty until the first answer arrives. */
    public static List<String> ids() {
        return IDS;
    }

    /**
     * Asks the server for the list. Called when a screen with an id field opens rather than per
     * keystroke: the answer is a whole list, and the server hands back a few bytes when it has not
     * changed since the last one.
     */
    public static void request() {
        SceNetworking.sendRequestRecipeIds(epoch);
    }

    /** The server says the list we hold is still the live one. */
    public static void keep(long serverEpoch) {
        epoch = serverEpoch;
        SceDebug.log(SceDebug.Category.CLIENT, "Recipe ids for completion still current ({} held)", IDS.size());
    }

    /** One batch of a new list. The old one stays usable until the last batch lands. */
    public static void accept(long serverEpoch, boolean first, boolean last, List<Identifier> batch) {
        if (first) {
            INCOMING.clear();
        }
        for (Identifier id : batch) {
            INCOMING.add(id.toString());
        }
        if (!last) {
            return;
        }
        IDS.clear();
        IDS.addAll(INCOMING);
        INCOMING.clear();
        epoch = serverEpoch;
        SceDebug.log(SceDebug.Category.CLIENT, "Recipe ids for completion: {} received", IDS.size());
    }

    /** Forgets the list on leaving a server, so none of it is carried into the next one. */
    public static void clear() {
        IDS.clear();
        INCOMING.clear();
        epoch = 0L;
    }
}
