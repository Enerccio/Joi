package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the LLM proposed for a model's appearance, stored on the model until the user applies or discards it.
 *
 * @param description how she looks, the parts that never change, in the second person ("You have long silver hair...")
 * @param toggles     names for the toggles: the toggle's id ({@code key:Headgear/Hide Halo}) → the name
 * @param parts       the toggles described apart: the toggle's id → a sentence for each state
 * @param model       the name of the model that proposed it
 * @param at          when
 */
public record AppearanceProposal(String description, Map<String, String> toggles, Map<String, Part> parts, String model, Date at) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param shown  how she looks there with the part shown
     * @param hidden how she looks there with it hidden (what is there instead)
     */
    public record Part(String shown, String hidden) {
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** The proposal in the JSON, null for none or one that can't be read. */
    public static AppearanceProposal fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            AppearanceProposal proposal = GSON.fromJson(json, AppearanceProposal.class);
            return proposal == null ? null : new AppearanceProposal(proposal.description(),
                    proposal.toggles() == null ? Map.of() : proposal.toggles(), proposal.parts() == null ? Map.of() : proposal.parts(),
                    proposal.model(), proposal.at());
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** The JSON object of a model's answer, alone or in a fenced block among other text. */
    static JsonObject object(String answer) {
        String text = answer == null ? "" : answer;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("The model did not answer with JSON");
        }
        try {
            return JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("The model's JSON can't be read: " + e.getMessage(), e);
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && !element.getAsString().isBlank() ? element.getAsString().strip() : null;
    }

    /**
     * Reads the answer to the request for the whole model: {@code description} and {@code toggles} (the toggle as the
     * model named it, a number or an id → the name; the caller matches them). The parts are added later.
     *
     * @throws IllegalArgumentException when there is no such object or no description
     */
    public static AppearanceProposal parse(String answer, String model, Date at) {
        JsonObject object = object(answer);
        String description = string(object, "description");
        if (description == null) {
            throw new IllegalArgumentException("The model gave no description");
        }
        Map<String, String> toggles = new LinkedHashMap<>();
        JsonElement names = object.get("toggles");
        if (names != null && names.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : names.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && !entry.getValue().getAsString().isBlank()) {
                    toggles.put(entry.getKey(), entry.getValue().getAsString().strip());
                }
            }
        }
        return new AppearanceProposal(description, toggles, Map.of(), model, at);
    }

    /**
     * Reads the answer to the request for one toggle: {@code shown} and {@code hidden}.
     *
     * @throws IllegalArgumentException when there is no such object or a state is missing
     */
    public static Part parsePart(String answer) {
        JsonObject object = object(answer);
        String shown = string(object, "shown");
        String hidden = string(object, "hidden");
        if (shown == null || hidden == null) {
            throw new IllegalArgumentException("The model did not describe both states");
        }
        return new Part(shown, hidden);
    }

    /** The same proposal with these names and parts. */
    public AppearanceProposal with(Map<String, String> toggles, Map<String, Part> parts) {
        return new AppearanceProposal(description, toggles, parts, model, at);
    }
}
