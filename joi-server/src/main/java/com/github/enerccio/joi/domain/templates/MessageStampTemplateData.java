package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the line before a message of the user or an event ({@code JoiPromptField.MESSAGE_STAMP}). {@code {{weekday}}},
 * {@code {{date}}} and {@code {{time}}} are the moment the message was sent, not the moment of the prompt: the line
 * of a message is the same every time it is in a prompt, so the model's cache of the prompt keeps.
 */
public class MessageStampTemplateData extends JoiTemplateData {

    /** How long after the message before it, in words ({@code 3 hours}); empty for the first message. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_GAP)
    private String gap;

    public String getGap() {
        return gap;
    }

    public void setGap(String gap) {
        this.gap = gap;
    }
}
