package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the memories added to a message of the user ({@code JoiPromptField.RECALLED_MEMORIES}): the ones that fit
 * what was said, the always recalled ones are in the system prompt.
 */
public class RecalledMemoriesTemplateData extends JoiTemplateData {

    /** The memories that fit this message, one a line. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MEMORIES)
    private String memories;

    public String getMemories() {
        return memories;
    }

    public void setMemories(String memories) {
        this.memories = memories;
    }
}
