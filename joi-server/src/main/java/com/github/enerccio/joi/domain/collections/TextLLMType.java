package com.github.enerccio.joi.domain.collections;

import com.github.enerccio.joi.loc.J_L;

/**
 * What a text model is used for. A model can be marked for several; the assistant's roles pick their model from the
 * models marked for them (see {@code DESIGN.md} → Model roles). Stored by name in the AI's extension attributes
 * ({@code TextModelTypes}), so the names never change.
 */
public enum TextLLMType {

    /** Small, cheap jobs next to the conversation: salience of events, short classifications, summaries. */
    AUXILIARY(J_L.ENUM_TEXT_LLM_TYPE_AUXILIARY),
    /** The conversation itself: fast first token, short turns, simple tools. */
    CHAT(J_L.ENUM_TEXT_LLM_TYPE_CHAT),
    /** The heavy role: long tool loops, planning, research, what {@code delegate} hands over. */
    DEEP_REASONING(J_L.ENUM_TEXT_LLM_TYPE_DEEP_REASONING);

    private final J_L label;

    TextLLMType(J_L label) {
        this.label = label;
    }

    /**
     * @return the key of the name shown in the UI
     */
    public J_L getLabel() {
        return label;
    }
}
