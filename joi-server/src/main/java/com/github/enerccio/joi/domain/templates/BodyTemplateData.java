package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the section of her system prompt about the body she is in ({@code JoiPromptField.BODY_PROMPT}, put into
 * the master template's {@code {{#body}}}).
 */
public class BodyTemplateData extends JoiTemplateData {

    /** The body's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NAME)
    private String bodyName;

    /** How she looks in it; empty when nobody has described it. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_APPEARANCE)
    private String appearance;

    /** The expressions her face can show (moods and custom ones), comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_EXPRESSIONS)
    private String expressions;

    /** The parts she can show or hide, each with "(shown)" or "(hidden)", comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PARTS)
    private String parts;

    /** The names of her other bodies, comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OTHER_BODIES)
    private String otherBodies;

    public String getBodyName() {
        return bodyName;
    }

    public void setBodyName(String bodyName) {
        this.bodyName = bodyName;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getExpressions() {
        return expressions;
    }

    public void setExpressions(String expressions) {
        this.expressions = expressions;
    }

    public String getParts() {
        return parts;
    }

    public void setParts(String parts) {
        this.parts = parts;
    }

    public String getOtherBodies() {
        return otherBodies;
    }

    public void setOtherBodies(String otherBodies) {
        this.otherBodies = otherBodies;
    }
}
