package org.mateof24.sce.core.state;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plain, loader-agnostic data model of the edits a pack applies to the recipe set. Persisted globally
 * by {@link RecipeStore} and applied to the live {@link net.minecraft.world.item.crafting.RecipeManager}
 * by {@link RecipeStateManager}.
 *
 * <p>{@code disabled} maps a recipe id to a snapshot of its original serialized JSON (may be {@code null}
 * if no snapshot was available), so a disabled recipe can be restored exactly later. {@code generated}
 * maps a new/overriding recipe id to its JSON. {@code hidden} is reserved for the index-only hide
 * feature (enforced in a later phase). {@code broken} is transient: ids that failed to parse on apply.
 */
public final class RecipeState {
    private final Map<Identifier, JsonObject> disabled = new LinkedHashMap<>();
    private final Map<Identifier, JsonObject> generated = new LinkedHashMap<>();
    private final Set<Identifier> disabledGenerated = new LinkedHashSet<>();
    private final Set<Identifier> hidden = new LinkedHashSet<>();
    private final Set<Identifier> broken = new LinkedHashSet<>();

    public Map<Identifier, JsonObject> disabled() {
        return disabled;
    }

    public Map<Identifier, JsonObject> generated() {
        return generated;
    }

    /** Generated recipes that are kept but toggled off, so they are stored yet not injected. */
    public Set<Identifier> disabledGenerated() {
        return disabledGenerated;
    }

    public boolean isGeneratedDisabled(Identifier id) {
        return disabledGenerated.contains(id);
    }

    public Set<Identifier> hidden() {
        return hidden;
    }

    public Set<Identifier> broken() {
        return broken;
    }

    public boolean isDisabled(Identifier id) {
        return disabled.containsKey(id);
    }

    public boolean isGenerated(Identifier id) {
        return generated.containsKey(id);
    }

    public void disable(Identifier id, JsonObject originalSnapshot) {
        disabled.put(id, originalSnapshot);
    }

    public boolean enable(Identifier id) {
        broken.remove(id);
        return disabled.remove(id) != null;
    }

    public void putGenerated(Identifier id, JsonObject json) {
        generated.put(id, json);
    }

    public boolean removeGenerated(Identifier id) {
        broken.remove(id);
        disabledGenerated.remove(id);
        return generated.remove(id) != null;
    }

    public void setGeneratedDisabled(Identifier id, boolean disabled) {
        if (disabled) {
            disabledGenerated.add(id);
        } else {
            disabledGenerated.remove(id);
        }
    }

    public void markBroken(Identifier id) {
        broken.add(id);
    }
}
