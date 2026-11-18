package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.joi.ui.dialogs.SystemPromptPreviewDialog;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.service.TemplateService;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.widgets.TemplateHints;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Templates tab of a {@link JoiDialog}: her own prompts ({@link JoiPromptField}). An empty one uses the user's
 * (else the built-in one), shown greyed out. A change is stored at once when the template is valid; an invalid one is
 * reported and not stored.
 */
@Configurable
public class JoiTemplatesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private TemplateService templateService;

    private final JoiDialog dialog;
    private final Map<JoiPromptField, TextAreaPopoverComponent> areas = new EnumMap<>(JoiPromptField.class);
    private boolean loading;

    public JoiTemplatesPart(JoiDialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public J_L caption() {
        return J_L.LABEL_TEMPLATES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.HELP_JOI_INHERITED));
        hint.addClassName(CssStyles.HINT_TEXT);
        VerticalLayout layout = new VerticalLayout(hint);
        layout.setWidthFull();
        for (JoiPromptField field : JoiPromptField.values()) {
            TextAreaPopoverComponent area = PromptAreas.create(loc, loc.getValue(PromptAreas.label(field)),
                    PromptAreas.TEMPLATE_HEIGHT, field.dataClass(), null);
            area.addValueChangeListener(e -> {
                if (!loading) {
                    save(field);
                }
            });
            areas.put(field, area);
            layout.add(area);
            if (field == JoiPromptField.MASTER_TEMPLATE) {
                Span help = new Span(loc.getValue(J_L.HELP_MASTER_TEMPLATE));
                help.addClassName(CssStyles.HINT_TEXT);
                Button preview = new Button(loc.getValue(J_L.LABEL_PREVIEW_SYSTEM_PROMPT), Solid.EYE.create(), e -> preview());
                preview.setThemeName("tertiary");
                layout.add(help, preview);
            }
        }
        layout.addClassName(CssStyles.FORM_PADDING_BOTTOM);
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        loading = true;
        try {
            for (JoiPromptField field : JoiPromptField.values()) {
                TextAreaPopoverComponent area = areas.get(field);
                area.setValue(StringUtils.defaultString(field.of(joi)));
                // what an empty one stands for: the user's, else the built-in one
                PromptAreas.showDefault(loc, area, field.dataClass(), personaService.resolve(field));
            }
        } finally {
            loading = false;
        }
    }

    private void save(JoiPromptField field) {
        TextAreaPopoverComponent area = areas.get(field);
        try {
            if (StringUtils.isNotBlank(area.getValue())) {
                TemplateService.ValidationResult result = templateService.isValidTemplate(area.getValue(), field.templateName(),
                        field.dataClass());
                if (!result.isValid()) {
                    Notification.error(loc.getValue(J_L.MSG_TEMPLATE_INVALID).formatted(result.errorMessage()));
                    return;
                }
                TemplateHints.showWarnings(loc, area, result);
            } else {
                TemplateHints.showWarnings(loc, area, null);
            }
            field.setOf(dialog.getJoi(), area.getValue());
            dialog.save();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void preview() {
        try {
            String template = areas.get(JoiPromptField.MASTER_TEMPLATE).getValue();
            SystemPromptPreviewDialog preview = new SystemPromptPreviewDialog(
                    StringUtils.isNotBlank(template) ? template : personaService.resolve(JoiPromptField.MASTER_TEMPLATE));
            preview.create();
            preview.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The area of a prompt, for tests. */
    public TextAreaPopoverComponent getArea(JoiPromptField field) {
        return areas.get(field);
    }
}
