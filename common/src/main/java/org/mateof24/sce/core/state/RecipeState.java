package org.mateof24.sce.core.state;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

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
    private final Map<ResourceLocation, JsonObject> disabled = new LinkedHashMap<>();
    private final Map<ResourceLocation, JsonObject> generated = new LinkedHashMap<>();
    private final Set<ResourceLocation> hidden = new LinkedHashSet<>();
    private final Set<ResourceLocation> broken = new LinkedHashSet<>();

    public Map<ResourceLocation, JsonObject> disabled() {
        return disabled;
    }

    public Map<ResourceLocation, JsonObject> generated() {
        return generated;
    }

    public Set<ResourceLocation> hidden() {
        return hidden;
    }

    public Set<ResourceLocation> broken() {
        return broken;
    }

    public boolean isDisabled(ResourceLocation id) {
        return disabled.containsKey(id);
    }

    public boolean isGenerated(ResourceLocation id) {
        return generated.containsKey(id);
    }

    public void disable(ResourceLocation id, JsonObject originalSnapshot) {
        disabled.put(id, originalSnapshot);
    }

    public boolean enable(ResourceLocation id) {
        broken.remove(id);
        return disabled.remove(id) != null;
    }

    public void putGenerated(ResourceLocation id, JsonObject json) {
        generated.put(id, json);
    }

    public boolean removeGenerated(ResourceLocation id) {
        broken.remove(id);
        return generated.remove(id) != null;
    }

    public void markBroken(ResourceLocation id) {
        broken.add(id);
    }
}
