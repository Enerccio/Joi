package com.github.enerccio.joi.domain.components;

import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * The {@link TextLLMType}s of an AI. The AI is the platform's entity, so Joi keeps its own data in the AI's dynamic
 * attributes under the key {@code "joi"} (no column, no migration; it travels with the AI in backups):
 * {@code {"joi": {"textTypes": ["CHAT", "AUXILIARY"]}}}. Written only through {@code AIService.save}.
 */
public final class TextModelTypes {

    static final String ATTRIBUTE = "joi";
    static final String TEXT_TYPES = "textTypes";

    private TextModelTypes() {
    }

    /**
     * @return the types the AI is marked for, empty when none (names Joi no longer knows are skipped)
     */
    public static Set<TextLLMType> get(AI ai) {
        Set<TextLLMType> types = EnumSet.noneOf(TextLLMType.class);
        if (ai == null || ai.getAttributes() == null || !(ai.getAttributes().get(ATTRIBUTE) instanceof JsonObject joi)
                || !(joi.get(TEXT_TYPES) instanceof JsonArray names)) {
            return types;
        }
        for (JsonElement name : names) {
            for (TextLLMType type : TextLLMType.values()) {
                if (name.isJsonPrimitive() && type.name().equals(name.getAsString())) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    /**
     * Marks the AI for the types (in the enum's order), keeping whatever else Joi stored on it. Save the AI afterwards.
     */
    public static void set(AI ai, Collection<TextLLMType> types) {
        if (ai.getAttributes() == null) {
            ai.setAttributes(new JsonObject());
        }
        JsonObject joi = ai.getAttributes().get(ATTRIBUTE) instanceof JsonObject existing ? existing : new JsonObject();
        JsonArray names = new JsonArray();
        EnumSet<TextLLMType> sorted = types.isEmpty() ? EnumSet.noneOf(TextLLMType.class) : EnumSet.copyOf(types);
        sorted.forEach(type -> names.add(type.name()));
        joi.add(TEXT_TYPES, names);
        ai.getAttributes().add(ATTRIBUTE, joi);
    }
}
