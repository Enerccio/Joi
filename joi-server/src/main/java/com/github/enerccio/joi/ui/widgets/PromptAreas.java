package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.persona.TemplateOverride;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.dialogs.TemplateCompareDialog;
import com.github.enerccio.marginalia.domain.templates.TemplateData;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.ui.widgets.TemplateHints;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;

/**
 * The text areas of Joi's prompts and texts: the full width, growing to fit the text with the platform's toggle
 * ({@link ResizableTextArea}), the inherited default as the placeholder, and the (i) popover with the default to fill
 * in and the variables of the template ({@link TemplateHints}). When the area sets a text that differs from the one it
 * inherits ({@link TemplateOverride}), the (i) is red and the popover says so; the popover's Compare opens the
 * {@link TemplateCompareDialog}, whose Save writes into the area.
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
            case WEEK_SUMMARY_PROMPT -> J_L.LABEL_WEEK_SUMMARY_PROMPT;
            case MONTH_SUMMARY_PROMPT -> J_L.LABEL_MONTH_SUMMARY_PROMPT;
            case YEAR_SUMMARY_PROMPT -> J_L.LABEL_YEAR_SUMMARY_PROMPT;
            case SUMMARY_EVENT -> J_L.LABEL_SUMMARY_EVENT;
            case FACTS_PROMPT -> J_L.LABEL_FACTS_PROMPT;
            case BODY_PROMPT -> J_L.LABEL_BODY_PROMPT;
            case SELF_IMAGE_PROMPT -> J_L.LABEL_SELF_IMAGE_PROMPT;
            case PART_IMAGE_PROMPT -> J_L.LABEL_PART_IMAGE_PROMPT;
            case OUTFIT_IMAGE_PROMPT -> J_L.LABEL_OUTFIT_IMAGE_PROMPT;
            case FIRST_BOOT_EVENT -> J_L.LABEL_FIRST_BOOT_EVENT;
            case CONNECTED_EVENT -> J_L.LABEL_CONNECTED_EVENT;
            case LOOKING_GLASS_ENTERED_EVENT -> J_L.LABEL_LOOKING_GLASS_ENTERED_EVENT;
            case LOOKING_GLASS_EXITED_EVENT -> J_L.LABEL_LOOKING_GLASS_EXITED_EVENT;
            case SERVER_RESTARTED_EVENT -> J_L.LABEL_SERVER_RESTARTED_EVENT;
            case MESSAGE_STAMP -> J_L.LABEL_MESSAGE_STAMP;
            case RECALLED_MEMORIES -> J_L.LABEL_RECALLED_MEMORIES;
            case IMAGE_NOTE -> J_L.LABEL_IMAGE_NOTE;
            case TOOL_IMAGES -> J_L.LABEL_TOOL_IMAGES;
            case IMAGE_DESCRIPTION_PROMPT -> J_L.LABEL_IMAGE_DESCRIPTION_PROMPT;
            case PART_REQUEST_EVENT -> J_L.LABEL_PART_REQUEST_EVENT;
            case WEB_TASK_PROMPT -> J_L.LABEL_WEB_TASK_PROMPT;
            case EXTENSION_TASK_PROMPT -> J_L.LABEL_EXTENSION_TASK_PROMPT;
            case TASK_EVENT -> J_L.LABEL_TASK_EVENT;
        };
    }

    /**
     * Shows what an empty area stands for (the placeholder and the popover), and marks the area when its text differs
     * from it. Called again when the inherited text changes (a reload).
     */
    public static void showDefault(Localization loc, TextAreaPopoverComponent area, Class<? extends TemplateData> dataClass,
                                   String defaultValue) {
        area.setPlaceholder(defaultValue == null ? "" : defaultValue);
        Component hints = TemplateHints.create(loc, area, area.getPopover(), dataClass, defaultValue);
        Mark mark = mark(area);
        mark.parent = defaultValue;
        mark.note = null;
        if (StringUtils.isNotBlank(defaultValue)) {
            Span note = new Span(loc.getValue(J_L.HELP_TEMPLATE_OVERRIDDEN));
            note.addClassName(JoiCssStyles.TEMPLATE_OVERRIDDEN_NOTE);
            Button compare = new Button(loc.getValue(J_L.LABEL_COMPARE_TEMPLATE), e -> compare(area, defaultValue));
            compare.setThemeName("small");
            compare.setWidthFull();
            mark.note = note;
            if (hints instanceof VerticalLayout layout) {
                // under the platform's Fill in the default, the first of the popover
                layout.addComponentAtIndex(0, note);
                layout.addComponentAtIndex(2, compare);
            } else {
                hints = new VerticalLayout(note, compare, hints);
            }
        }
        area.setPopoverContent(hints);
        updateMark(area);
    }

    /** Whether the area's text differs from the one it inherits (its (i) is red). */
    public static boolean isOverridden(TextAreaPopoverComponent area) {
        return area.getInfoIcon().hasClassName(JoiCssStyles.TEMPLATE_OVERRIDDEN);
    }

    /** Opens the compare dialog of the area against what it inherits. */
    public static TemplateCompareDialog compare(TextAreaPopoverComponent area, String parent) {
        area.getPopover().close();
        TemplateCompareDialog dialog = new TemplateCompareDialog(area.getLabel(), parent, area);
        dialog.create();
        dialog.open();
        return dialog;
    }

    /** Opens the compare dialog of the area against what it inherits, null when it inherits nothing. */
    public static TemplateCompareDialog compare(TextAreaPopoverComponent area) {
        Mark mark = ComponentUtil.getData(area, Mark.class);
        return mark == null || StringUtils.isBlank(mark.parent) ? null : compare(area, mark.parent);
    }

    private static Mark mark(TextAreaPopoverComponent area) {
        Mark mark = ComponentUtil.getData(area, Mark.class);
        if (mark == null) {
            mark = new Mark();
            ComponentUtil.setData(area, Mark.class, mark);
            area.addValueChangeListener(e -> updateMark(area));
        }
        return mark;
    }

    private static void updateMark(TextAreaPopoverComponent area) {
        Mark mark = ComponentUtil.getData(area, Mark.class);
        boolean differs = mark != null && TemplateOverride.differs(area.getValue(), mark.parent);
        area.getInfoIcon().setClassName(JoiCssStyles.TEMPLATE_OVERRIDDEN, differs);
        if (mark != null && mark.note != null) {
            mark.note.setVisible(differs);
        }
    }

    /** What the area inherits and the line of its popover that says it differs. */
    private static final class Mark {
        private String parent;
        private Span note;
    }
}
