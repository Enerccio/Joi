package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of what she is told with the pictures of her tools' results ({@code JoiPromptField.TOOL_IMAGES}): a node's tool may
 * answer with pictures (a file she looked at, a screenshot).
 */
public class ToolImagesTemplateData extends JoiTemplateData {

    /** The functions whose results brought the pictures. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TOOL_IMAGES_TOOLS)
    private String tools;

    /** What a model that can see wrote about each picture, empty when her chat model sees them itself. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TOOL_IMAGES_SHOWS)
    private String shows;

    public String getTools() {
        return tools;
    }

    public void setTools(String tools) {
        this.tools = tools;
    }

    public String getShows() {
        return shows;
    }

    public void setShows(String shows) {
        this.shows = shows;
    }
}
