package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The state of a body's parts: for each part described apart ({@link DescribedPart}) that was switched, the state it
 * is in. A part not in it, or whose state is gone from the model, is in its default state; the toggles in no part stay
 * as by default. Stored on the body as JSON ({@code part id → state id}), so it survives a renamed state or group.
 */
public final class BodyState {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BodyState() {
    }

    /** The part's id → the state's id; empty for none or for JSON that can't be read. */
    public static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> read = GSON.fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            return read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
        } catch (JsonParseException e) {
            return new LinkedHashMap<>();
        }
    }

    /** As stored, null for none. */
    public static String toJson(Map<String, String> states) {
        return states == null || states.isEmpty() ? null : GSON.toJson(states);
    }

    /** The state the part is in: the one stored when the part still has it, else its default (or the nearest). */
    public static DescribedPart.State stateOf(Model model, DescribedPart part, Map<String, String> states) {
        DescribedPart.State stored = state(part, states.get(part.id()));
        if (stored != null) {
            return stored;
        }
        DescribedPart.State byDefault = state(part, part.defaultStateId());
        if (byDefault != null) {
            return byDefault;
        }
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        return part.stateIn(model.defaultShown(capabilities));
    }

    /** The state of the part with the id, null for none. */
    public static DescribedPart.State state(DescribedPart part, String stateId) {
        return stateId == null ? null : part.states().stream().filter(state -> state.id().equals(stateId)).findFirst().orElse(null);
    }

    /**
     * What is seen in this state of the parts: each toggle's own state ({@link #toggles}), shown only while the toggles
     * above it are ({@link ToggleTree}).
     */
    public static Map<String, Boolean> effective(Model model, Map<String, String> states) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        return ToggleTree.of(model, capabilities).effective(toggles(model, states));
    }

    /**
     * Whether each toggle of the model is shown in this state of its parts: as by default, then each switched part's
     * toggles as its state says. A toggle's own state: see {@link #effective} for what is seen.
     */
    public static Map<String, Boolean> toggles(Model model, Map<String, String> states) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        if (capabilities == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Boolean> shown = model.defaultShown(capabilities);
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            DescribedPart.State state = state(part, states.get(part.id()));
            if (state != null) {
                shown.putAll(state.shown());
            }
        }
        return shown;
    }
}
