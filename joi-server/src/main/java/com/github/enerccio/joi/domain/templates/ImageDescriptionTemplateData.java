package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the request to describe an image of the user for a chat model that cannot see
 * ({@code JoiPromptField.IMAGE_DESCRIPTION_PROMPT}).
 */
public class ImageDescriptionTemplateData extends JoiTemplateData {

    /** What the user wrote with the image, null when nothing. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_USER_TEXT)
    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
