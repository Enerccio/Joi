package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the note that stands for an image of the user in a message when the chat model cannot see
 * ({@code JoiPromptField.IMAGE_NOTE}).
 */
public class ImageNoteTemplateData extends JoiTemplateData {

    /** What a model that can see wrote about the image. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_IMAGE_DESCRIPTION)
    private String shows;

    public String getShows() {
        return shows;
    }

    public void setShows(String shows) {
        this.shows = shows;
    }
}
