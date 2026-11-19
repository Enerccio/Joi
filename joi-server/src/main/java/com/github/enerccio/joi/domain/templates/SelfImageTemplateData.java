package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the request for a body's self-image ({@code JoiPromptField.SELF_IMAGE_PROMPT}): what the multimodal
 * model gets next to the images. {@code {{char}}} is the assistant whose body it is.
 */
public class SelfImageTemplateData extends JoiTemplateData {

    /** The body's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NAME)
    private String bodyName;

    /** The snapshots, in the order of the images ("full front, full back, ..."); empty when there are none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SNAPSHOTS)
    private String snapshots;

    /** How many images of the user follow the snapshots; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_REFERENCES)
    private String references;

    /** The user's notes. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NOTES)
    private String notes;

    /** The description the body has now. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CURRENT_DESCRIPTION)
    private String currentDescription;

    /** A line per toggle: its number, the mesh / key, its name now, shown or hidden as the model comes. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TOGGLES)
    private String toggles;

    public String getBodyName() {
        return bodyName;
    }

    public void setBodyName(String bodyName) {
        this.bodyName = bodyName;
    }

    public String getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(String snapshots) {
        this.snapshots = snapshots;
    }

    public String getReferences() {
        return references;
    }

    public void setReferences(String references) {
        this.references = references;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCurrentDescription() {
        return currentDescription;
    }

    public void setCurrentDescription(String currentDescription) {
        this.currentDescription = currentDescription;
    }

    public String getToggles() {
        return toggles;
    }

    public void setToggles(String toggles) {
        this.toggles = toggles;
    }
}
