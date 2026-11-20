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
 * <p>
 * An outfit sets toggles, parts or not: they are kept in the same map under {@code @<the toggle's id>} ({@link #OUTFIT})
 * with the state {@link DescribedPart#SHOWN} or {@link DescribedPart#HIDDEN}. They come under the parts: a part
 * switched after the outfit was put on is what it was switched to.
 */
public final class BodyState {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** The prefix of the key of a toggle an outfit set: {@code @<the toggle's id>}. */
    public static final String OUTFIT = "@";

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
        // an outfit set its toggles: the state is the nearest to them
        if (part.toggles().stream().anyMatch(toggle -> states.containsKey(OUTFIT + toggle.id()))) {
            return part.stateIn(toggles(model, states));
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
        states.forEach((key, state) -> {
            if (key.startsWith(OUTFIT)) {
                shown.put(key.substring(OUTFIT.length()), DescribedPart.SHOWN.equals(state));
            }
        });
        for (DescribedPart part : DescribedPart.of(model, capabilities)) {
            DescribedPart.State state = state(part, states.get(part.id()));
            if (state != null) {
                shown.putAll(state.shown());
            }
        }
        return shown;
    }

    /**
     * The state with the outfit put on: the toggles the outfit sets, no part switched (the parts show the outfit).
     *
     * @return the new state of the parts, for {@code Body.setPartStateMap}
     */
    public static Map<String, String> wearing(Outfit outfit) {
        Map<String, String> states = new LinkedHashMap<>();
        outfit.toggles().forEach((id, shown) -> states.put(OUTFIT + id, shown ? DescribedPart.SHOWN : DescribedPart.HIDDEN));
        return states;
    }

    /** The state of every toggle of the model as it is (to make an outfit of): its own state, not what is seen. */
    public static Map<String, Boolean> shownNow(Model model, Map<String, String> states) {
        return toggles(model, states);
    }
}
