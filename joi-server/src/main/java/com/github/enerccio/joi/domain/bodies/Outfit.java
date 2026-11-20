package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An outfit of a body: a name, a description of how she looks in it as a whole, and the state of every toggle of the
 * body's model (the toggle's id → whether it is shown). It is a preset: putting it on sets the toggles to that state.
 * The description is the user's, or proposed by a multimodal model from a photoshoot of the model in the outfit, and is
 * what she is told of the outfit in her lists and in her look while she wears it.
 * <p>
 * A body keeps its outfits as JSON ({@link #fromJson}, {@link #toJson}); the id is stable, so a rename does not lose it.
 *
 * @param id          a uuid
 * @param name        what she calls it
 * @param description how she looks in it, in the second person; null for not described
 * @param toggles     the toggle's id → whether it is shown, for every toggle of the model when it was made; a toggle
 *                    not in it is as by default
 */
public record Outfit(String id, String name, String description, Map<String, Boolean> toggles) {

    private static final Gson GSON = new Gson();

    public Outfit {
        toggles = toggles == null ? Map.of() : Map.copyOf(toggles);
    }

    /** A new outfit with a new id. */
    public static Outfit create(String name, String description, Map<String, Boolean> toggles) {
        return new Outfit(UUID.randomUUID().toString(), name, StringUtils.defaultIfBlank(StringUtils.strip(description), null), toggles);
    }

    public Outfit named(String name) {
        return new Outfit(id, name, description, toggles);
    }

    public Outfit described(String description) {
        return new Outfit(id, name, StringUtils.defaultIfBlank(StringUtils.strip(description), null), toggles);
    }

    public Outfit with(Map<String, Boolean> toggles) {
        return new Outfit(id, name, description, toggles);
    }

    /** Whether the toggles of the model are in this outfit's state ({@code state}: the toggle's id → shown). */
    public boolean matches(Model model, ModelCapabilities capabilities, Map<String, Boolean> state) {
        if (capabilities == null) {
            return false;
        }
        for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
            boolean wanted = toggles.getOrDefault(toggle.id(), model.shownByDefault(toggle));
            if (state.getOrDefault(toggle.id(), model.shownByDefault(toggle)) != wanted) {
                return false;
            }
        }
        return true;
    }

    /** The outfits stored; empty for none or for JSON that can't be read. */
    public static List<Outfit> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Outfit> read = GSON.fromJson(json, new TypeToken<List<Outfit>>() {
            }.getType());
            List<Outfit> outfits = new ArrayList<>();
            for (Outfit outfit : read == null ? List.<Outfit>of() : read) {
                if (outfit != null && outfit.id() != null) {
                    outfits.add(new Outfit(outfit.id(), outfit.name(), outfit.description(),
                            outfit.toggles() == null ? new LinkedHashMap<>() : outfit.toggles()));
                }
            }
            return outfits;
        } catch (JsonParseException e) {
            return new ArrayList<>();
        }
    }

    /** As stored, null for none. */
    public static String toJson(List<Outfit> outfits) {
        return outfits == null || outfits.isEmpty() ? null : GSON.toJson(outfits);
    }
}
