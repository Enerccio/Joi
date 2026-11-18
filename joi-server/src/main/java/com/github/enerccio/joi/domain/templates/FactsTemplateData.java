package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the extraction of facts to remember ({@code JoiPromptField.FACTS_PROMPT}).
 */
public class FactsTemplateData extends JoiTemplateData {

    /** The transcript of the conversation. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CONVERSATION)
    private String conversation;

    /** The facts already remembered. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_KNOWN_MEMORIES)
    private String memories;

    public String getConversation() {
        return conversation;
    }

    public void setConversation(String conversation) {
        this.conversation = conversation;
    }

    public String getMemories() {
        return memories;
    }

    public void setMemories(String memories) {
        this.memories = memories;
    }

}
