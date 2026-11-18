package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.defaults.PersonalityDefault;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.persona.PersonalitySetting;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits the user's version of a personality: a built-in one (an empty field keeps the built-in text, shown as the
 * placeholder) or one of their own (a new one gets a key). The texts may use {@code {{user}}} and {@code {{char}}}.
 */
@Configurable
public class PersonalityDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private PersonaService personaService;

    private final String key;
    private Runnable onSave = () -> {
    };

    private PersonalityDefault builtIn;
    private TextField keyField;
    private TextField nameField;
    private TextAreaPopoverComponent promptArea;
    private final Map<String, TextAreaPopoverComponent> expressionAreas = new LinkedHashMap<>();

    /**
     * @param key the key of the personality to edit, null for a new one of the user
     */
    public PersonalityDialog(String key) {
        this.key = key;
    }

    public void setOnSave(Runnable onSave) {
        this.onSave = onSave == null ? () -> {
        } : onSave;
    }

    public void create() throws Exception {
        setHeaderTitle(loc.getValue(J_L.LABEL_PERSONALITY));
        setWidth("min(56em, 95vw)");
        setCloseOnOutsideClick(false);

        builtIn = key == null ? null : personaService.defaults().personalities().stream()
                .filter(p -> p.key().equals(key)).findFirst().orElse(null);
        PersonalitySetting own = key == null ? new PersonalitySetting() : personaService.getUserSetting().getPersonalities().stream()
                .filter(p -> key.equals(p.getKey())).findFirst().orElse(new PersonalitySetting(key));

        keyField = new TextField(loc.getValue(J_L.LABEL_KEY));
        keyField.setValue(StringUtils.defaultString(key));
        keyField.setReadOnly(key != null);
        keyField.setHelperText(key == null ? loc.getValue(J_L.HELP_KEY) : null);
        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setValue(StringUtils.defaultString(own.getName()));
        nameField.setPlaceholder(builtIn == null ? "" : builtIn.name());
        nameField.setWidth("20em");
        HorizontalLayout names = new HorizontalLayout(keyField, nameField);
        names.setPadding(false);
        names.setWrap(true);

        promptArea = PromptAreas.create(loc, loc.getValue(J_L.LABEL_PROMPT), PromptAreas.TEXT_HEIGHT, ChatTemplateData.class,
                builtIn == null ? null : builtIn.prompt());
        promptArea.setValue(StringUtils.defaultString(own.getPrompt()));
        promptArea.setHelperText(loc.getValue(J_L.HELP_PERSONALITY));

        VerticalLayout expressions = new VerticalLayout();
        expressions.setPadding(false);
        List<Mood> moods = personaService.moods();
        for (Mood mood : moods) {
            String inherited = builtIn != null && builtIn.moodExpressions().containsKey(mood.key())
                    ? builtIn.moodExpressions().get(mood.key()) : mood.prompt();
            TextAreaPopoverComponent area = PromptAreas.create(loc, mood.name(), PromptAreas.TEXT_HEIGHT, ChatTemplateData.class, inherited);
            area.setValue(StringUtils.defaultString(own.getMoodExpressions().get(mood.key())));
            area.setHelperText(loc.getValue(J_L.HELP_MOOD_EXPRESSION));
            expressionAreas.put(mood.key(), area);
            expressions.add(area);
        }
        Details moodsDetails = new Details(loc.getValue(J_L.LABEL_MOOD_EXPRESSIONS), expressions);
        moodsDetails.setWidthFull();

        VerticalLayout content = new VerticalLayout(names, promptArea, moodsDetails);
        content.setPadding(false);
        add(content);

        Button cancelButton = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        Button saveButton = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        saveButton.setThemeName("primary");
        getFooter().add(cancelButton, saveButton);
        DialogFocus.onOpen(this, key == null ? keyField : nameField);
        DialogShortcuts.submitWith(saveButton, keyField, nameField);
    }

    private void save() {
        try {
            String newKey = key != null ? key : keyField.getValue().strip();
            if (key == null) {
                if (!newKey.matches("[A-Za-z0-9-]+")) {
                    keyField.setErrorMessage(loc.getValue(J_L.MSG_INVALID_KEY));
                    keyField.setInvalid(true);
                    return;
                }
                if (personaService.personality(newKey) != null) {
                    keyField.setErrorMessage(loc.getValue(J_L.MSG_KEY_TAKEN));
                    keyField.setInvalid(true);
                    return;
                }
            }
            PersonalitySetting setting = new PersonalitySetting(newKey);
            setting.setName(StringUtils.defaultIfBlank(nameField.getValue().strip(), null));
            setting.setPrompt(StringUtils.defaultIfBlank(promptArea.getValue().strip(), null));
            Map<String, String> expressions = new LinkedHashMap<>();
            expressionAreas.forEach((mood, area) -> {
                if (StringUtils.isNotBlank(area.getValue())) {
                    expressions.put(mood, area.getValue().strip());
                }
            });
            setting.setMoodExpressions(expressions);
            personaService.savePersonality(setting);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        onSave.run();
        close();
    }

    /** The key field, for tests. */
    public TextField getKeyField() {
        return keyField;
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The prompt, for tests. */
    public TextAreaPopoverComponent getPromptArea() {
        return promptArea;
    }

    /** The area of how the mood sounds, for tests. */
    public TextAreaPopoverComponent getExpressionArea(String mood) {
        return expressionAreas.get(mood);
    }

    /** Saves as the save button does, for tests. */
    public void clickSave() {
        save();
    }
}
