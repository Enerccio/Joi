package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the summary of a conversation ({@code JoiPromptField.SUMMARY_PROMPT}).
 */
public class SummaryTemplateData extends JoiTemplateData {

    /** The transcript of the conversation. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CONVERSATION)
    private String conversation;

    public String getConversation() {
        return conversation;
    }

    public void setConversation(String conversation) {
        this.conversation = conversation;
    }

}
