package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.marginalia.domain.templates.TemplateData;

import java.util.Objects;

/**
 * The base of Joi's template data: {@code {{char}}} is the assistant's name and {@code {{user}}} the user's (in
 * Marginalia both are the point-of-view character of the story).
 */
public abstract class JoiTemplateData extends TemplateData {

    private String assistantName;
    private String userName;

    /** Sets the two names. */
    public JoiTemplateData names(String assistantName, String userName) {
        this.assistantName = assistantName;
        this.userName = userName;
        return this;
    }

    @Override
    public String user() {
        return Objects.toString(userName, "");
    }

    @Override
    public String character() {
        return Objects.toString(assistantName, "");
    }
}
