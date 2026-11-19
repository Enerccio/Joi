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
 * What the LLM proposed for a body's self-image, stored on the body until the user applies or discards it.
 *
 * @param description how she looks, in the second person ("You have long silver hair...")
 * @param toggles     names for the toggles: the toggle's id ({@code key:Headgear/Hide Halo}) → the name
 * @param model       the name of the model that proposed it
 * @param at          when
 */
public record BodyProposal(String description, Map<String, String> toggles, String model, Date at) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String toJson() {
        return GSON.toJson(this);
    }

    /** The proposal in the JSON, null for none or one that can't be read. */
    public static BodyProposal fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            BodyProposal proposal = GSON.fromJson(json, BodyProposal.class);
            return proposal == null ? null : new BodyProposal(proposal.description(),
                    proposal.toggles() == null ? Map.of() : proposal.toggles(), proposal.model(), proposal.at());
        } catch (JsonParseException e) {
            return null;
        }
    }

    /**
     * Reads the model's answer: a JSON object with {@code description} and {@code toggles} (id → name), alone or in
     * a fenced block among other text. The toggles are as the model named them (numbers, ids); the caller matches them.
     *
     * @throws IllegalArgumentException when there is no such object or no description
     */
    public static BodyProposal parse(String answer, String model, Date at) {
        String text = answer == null ? "" : answer;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("The model did not answer with JSON");
        }
        JsonObject object;
        try {
            object = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("The model's JSON can't be read: " + e.getMessage(), e);
        }
        JsonElement description = object.get("description");
        if (description == null || !description.isJsonPrimitive() || description.getAsString().isBlank()) {
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
        return new BodyProposal(description.getAsString().strip(), toggles, model, at);
    }
}
