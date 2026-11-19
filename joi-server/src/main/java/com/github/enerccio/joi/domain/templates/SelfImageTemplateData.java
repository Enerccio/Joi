package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the request for a model's appearance ({@code JoiPromptField.SELF_IMAGE_PROMPT}): what the multimodal
 * model gets next to the snapshots of the whole model and the user's images.
 */
public class SelfImageTemplateData extends JoiTemplateData {

    /** The model's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MODEL_NAME)
    private String modelName;

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

    /** The names of the toggles described apart (left out of the description), comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_DESCRIBED_PARTS)
    private String parts;

    /** A line per toggle: its number, the mesh / key, its name now, shown or hidden as the model comes. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TOGGLES)
    private String toggles;

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
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

    public String getParts() {
        return parts;
    }

    public void setParts(String parts) {
        this.parts = parts;
    }
}
