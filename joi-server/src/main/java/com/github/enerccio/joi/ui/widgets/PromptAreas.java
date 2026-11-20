package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.domain.templates.TemplateData;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.ui.widgets.TemplateHints;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;

/**
 * The text areas of Joi's prompts and texts: the full width, growing to fit the text with the platform's toggle
 * ({@link ResizableTextArea}), the inherited default as the placeholder, and the (i) popover with the default to fill
 * in and the variables of the template ({@link TemplateHints}).
 */
public final class PromptAreas {

    /** The height of a template in its fixed mode. */
    public static final String TEMPLATE_HEIGHT = "240px";

    /** The height of a short text (a personality, a mood) in its fixed mode. */
    public static final String TEXT_HEIGHT = "120px";

    private PromptAreas() {
    }

    /**
     * @param dataClass    the data of the template (its variables are listed in the popover), null for a plain text
     * @param defaultValue what an empty area stands for, null for nothing
     */
    public static TextAreaPopoverComponent create(Localization loc, String label, String height,
                                                  Class<? extends TemplateData> dataClass, String defaultValue) {
        TextAreaPopoverComponent area = new TextAreaPopoverComponent(label);
        area.setWidthFull();
        ResizableTextArea.install(loc, area, height);
        showDefault(loc, area, dataClass, defaultValue);
        return area;
    }

    /** The label of the area of a prompt of the chain. */
    public static J_L label(JoiPromptField field) {
        return switch (field) {
            case MASTER_TEMPLATE -> J_L.LABEL_MASTER_TEMPLATE;
            case SUMMARY_PROMPT -> J_L.LABEL_SUMMARY_PROMPT;
            case DAY_SUMMARY_PROMPT -> J_L.LABEL_DAY_SUMMARY_PROMPT;
            case FACTS_PROMPT -> J_L.LABEL_FACTS_PROMPT;
            case BODY_PROMPT -> J_L.LABEL_BODY_PROMPT;
            case SELF_IMAGE_PROMPT -> J_L.LABEL_SELF_IMAGE_PROMPT;
            case PART_IMAGE_PROMPT -> J_L.LABEL_PART_IMAGE_PROMPT;
            case FIRST_BOOT_EVENT -> J_L.LABEL_FIRST_BOOT_EVENT;
            case CONNECTED_EVENT -> J_L.LABEL_CONNECTED_EVENT;
            case LOOKING_GLASS_ENTERED_EVENT -> J_L.LABEL_LOOKING_GLASS_ENTERED_EVENT;
            case LOOKING_GLASS_EXITED_EVENT -> J_L.LABEL_LOOKING_GLASS_EXITED_EVENT;
            case PART_REQUEST_EVENT -> J_L.LABEL_PART_REQUEST_EVENT;
        };
    }

    /** Shows what an empty area stands for (the placeholder and the popover). */
    public static void showDefault(Localization loc, TextAreaPopoverComponent area, Class<? extends TemplateData> dataClass,
                                   String defaultValue) {
        area.setPlaceholder(defaultValue == null ? "" : defaultValue);
        area.setPopoverContent(TemplateHints.create(loc, area, area.getPopover(), dataClass, defaultValue));
    }
}
