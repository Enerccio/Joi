package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.ResizableTextArea;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * Shows the system prompt of a chat as it would be sent: the master template (the one being edited, or the resolved
 * one) with the user's personality and a chosen mood. The sections the server fills later (memories, profile, body...)
 * show what goes there, so every section of the template is visible.
 */
@Configurable
public class SystemPromptPreviewDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private PersonaService personaService;

    private final Joi joi;
    private final String template;
    private Select<Mood> moodSelect;
    private TextArea output;

    /**
     * @param template the master template being edited, blank for the resolved one
     */
    public SystemPromptPreviewDialog(String template) {
        this(null, template);
    }

    /**
     * @param joi      the assistant whose prompt it is (her names and personality), null for the user's settings
     * @param template the master template being edited, blank for the resolved one
     */
    public SystemPromptPreviewDialog(Joi joi, String template) {
        this.joi = joi;
        this.template = template;
    }

    public void create() throws Exception {
        setHeaderTitle(loc.getValue(J_L.LABEL_PREVIEW_SYSTEM_PROMPT));
        setWidth("min(60em, 95vw)");

        moodSelect = new Select<>();
        moodSelect.setLabel(loc.getValue(J_L.LABEL_MOOD));
        moodSelect.setItems(personaService.moods());
        moodSelect.setItemLabelGenerator(mood -> mood == null ? "" : mood.name());
        moodSelect.setValue(personaService.moods().getFirst());
        moodSelect.addValueChangeListener(e -> render());

        output = new TextArea();
        output.setWidthFull();
        output.setReadOnly(true);
        ResizableTextArea.install(loc, output, "420px");

        VerticalLayout content = new VerticalLayout(moodSelect, output);
        content.setPadding(false);
        add(content);
        getFooter().add(new Button(loc.getValue(L.LABEL_CLOSE), e -> close()));
        render();
    }

    private void render() {
        ChatTemplateData data = new ChatTemplateData();
        data.setProfile("(" + loc.getValue(J_L.DESC_TEMPLATE_PROFILE) + ")");
        data.setMemories("(" + loc.getValue(J_L.DESC_TEMPLATE_MEMORIES) + ")");
        data.setConversations("(" + loc.getValue(J_L.DESC_TEMPLATE_CONVERSATIONS) + ")");
        data.setBody("(" + loc.getValue(J_L.DESC_TEMPLATE_BODY) + ")");
        data.setNode("(" + loc.getValue(J_L.DESC_TEMPLATE_NODE) + ")");
        data.setVoice("(" + loc.getValue(J_L.DESC_TEMPLATE_VOICE) + ")");
        try {
            Mood mood = moodSelect.getValue();
            output.setValue(personaService.renderChatSystemPrompt(joi, template, mood == null ? null : mood.key(), data));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The rendered prompt, for tests. */
    public String getText() {
        return output.getValue();
    }
}
