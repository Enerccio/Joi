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
 * @param parts       the parts described apart ({@link DescribedPart}): the part's id → the state's id → its sentence
 * @param model       the name of the model that proposed it
 * @param at          when
 */
public record AppearanceProposal(String description, Map<String, String> toggles, Map<String, Map<String, String>> parts,
                                 String model, Date at) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

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
     * Reads the answer to the request for one part: a sentence for each state, by its number in the request (its id
     * or its name are taken too, and "shown" / "hidden" for a toggle).
     *
     * @return the state's id → its sentence
     * @throws IllegalArgumentException when there is no such object or a state is missing
     */
    public static Map<String, String> parsePart(String answer, DescribedPart part) {
        JsonObject object = object(answer);
        Map<String, String> sentences = new LinkedHashMap<>();
        for (int i = 0; i < part.states().size(); i++) {
            DescribedPart.State state = part.states().get(i);
            String sentence = null;
            for (String key : new String[]{String.valueOf(i + 1), state.id(), state.name()}) {
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (sentence == null && entry.getKey().strip().equalsIgnoreCase(key)) {
                        sentence = string(object, entry.getKey());
                    }
                }
            }
            if (sentence == null) {
                throw new IllegalArgumentException("The model did not describe the state " + state.name());
            }
            sentences.put(state.id(), sentence);
        }
        return sentences;
    }

    /** The same proposal with these names and parts. */
    public AppearanceProposal with(Map<String, String> toggles, Map<String, Map<String, String>> parts) {
        return new AppearanceProposal(description, toggles, parts, model, at);
    }
}
