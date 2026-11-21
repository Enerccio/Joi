package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the summary of a conversation ({@code JoiPromptField.SUMMARY_PROMPT}).
 */
public class SummaryTemplateData extends JoiTemplateData {

    /** The transcript of the conversation. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CONVERSATION)
    private String conversation;

    /** When the conversation took place, as dates and times. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SPAN)
    private String span;

    public String getSpan() {
        return span;
    }

    public void setSpan(String span) {
        this.span = span;
    }

    public String getConversation() {
        return conversation;
    }

    public void setConversation(String conversation) {
        this.conversation = conversation;
    }

}
