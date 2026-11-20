package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the request for an outfit ({@code JoiPromptField.OUTFIT_IMAGE_PROMPT}): what the multimodal model gets
 * next to the renders of the model in the outfit (the photoshoot).
 */
public class OutfitImageTemplateData extends JoiTemplateData {

    /** The model's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MODEL_NAME)
    private String modelName;

    /** The outfit's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OUTFIT_NAME)
    private String outfitName;

    /** The renders, in the order of the images ("full front, full three quarter..."). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SNAPSHOTS)
    private String snapshots;

    /** The meshes shown in the outfit, comma separated; empty when not known. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OUTFIT_SHOWN)
    private String shown;

    /** The description of the whole model (what never changes). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_APPEARANCE)
    private String appearance;

    /** The user's notes. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NOTES)
    private String notes;

    /** The description the outfit has now; empty for none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CURRENT_DESCRIPTION)
    private String currentDescription;

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getOutfitName() {
        return outfitName;
    }

    public void setOutfitName(String outfitName) {
        this.outfitName = outfitName;
    }

    public String getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(String snapshots) {
        this.snapshots = snapshots;
    }

    public String getShown() {
        return shown;
    }

    public void setShown(String shown) {
        this.shown = shown;
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

    public String getCurrentDescription() {
        return currentDescription;
    }

    public void setCurrentDescription(String currentDescription) {
        this.currentDescription = currentDescription;
    }
}
