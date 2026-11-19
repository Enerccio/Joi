package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the request for one part of a model described apart ({@code JoiPromptField.PART_IMAGE_PROMPT}): what
 * the multimodal model gets next to the close-ups of the part, one per state (a toggle shown and hidden, or the
 * states of a group the user made).
 */
public class PartImageTemplateData extends JoiTemplateData {

    /** The model's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MODEL_NAME)
    private String modelName;

    /** What the part is called ("visor", "eyes"). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PART_NAME)
    private String partName;

    /** The mesh, or the mesh / the shape key, that shows or hides it; the toggles of a group. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PART_SOURCE)
    private String partSource;

    /** The description of the whole model (what never changes). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_APPEARANCE)
    private String appearance;

    /** The user's notes. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NOTES)
    private String notes;

    /** The states, numbered in the order of the images ("1 | bare: blindfold hidden, iris shown"). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PART_STATES)
    private String states;

    public String getStates() {
        return states;
    }

    public void setStates(String states) {
        this.states = states;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPartName() {
        return partName;
    }

    public void setPartName(String partName) {
        this.partName = partName;
    }

    public String getPartSource() {
        return partSource;
    }

    public void setPartSource(String partSource) {
        this.partSource = partSource;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
