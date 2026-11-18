package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.defaults.MoodDefault;
import com.github.enerccio.joi.domain.persona.MoodSetting;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.text.Delivery;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * Edits the user's version of a mood: a built-in one (an empty field keeps the built-in value, shown as the placeholder)
 * or one of their own (a new one gets a key).
 */
@Configurable
public class MoodDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private PersonaService personaService;

    private final String key;
    private Runnable onSave = () -> {
    };

    private TextField keyField;
    private TextField nameField;
    private TextAreaPopoverComponent promptArea;
    private IntegerField weightField;
    private Select<Delivery> deliverySelect;

    /**
     * @param key the key of the mood to edit, null for a new one of the user
     */
    public MoodDialog(String key) {
        this.key = key;
    }

    public void setOnSave(Runnable onSave) {
        this.onSave = onSave == null ? () -> {
        } : onSave;
    }

    public void create() throws Exception {
        setHeaderTitle(loc.getValue(J_L.LABEL_MOOD));
        setWidth("min(48em, 95vw)");
        setCloseOnOutsideClick(false);

        MoodDefault builtIn = key == null ? null : personaService.defaults().moods().stream()
                .filter(m -> m.key().equals(key)).findFirst().orElse(null);
        MoodSetting own = key == null ? new MoodSetting() : personaService.getUserSetting().getMoods().stream()
                .filter(m -> key.equals(m.getKey())).findFirst().orElse(new MoodSetting(key));

        keyField = new TextField(loc.getValue(J_L.LABEL_KEY));
        keyField.setValue(StringUtils.defaultString(key));
        keyField.setReadOnly(key != null);
        keyField.setHelperText(key == null ? loc.getValue(J_L.HELP_KEY) : null);
        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setValue(StringUtils.defaultString(own.getName()));
        nameField.setPlaceholder(builtIn == null ? "" : builtIn.name());
        HorizontalLayout names = new HorizontalLayout(keyField, nameField);
        names.setPadding(false);
        names.setWrap(true);

        promptArea = PromptAreas.create(loc, loc.getValue(J_L.LABEL_PROMPT), PromptAreas.TEXT_HEIGHT, ChatTemplateData.class,
                builtIn == null ? null : builtIn.prompt());
        promptArea.setValue(StringUtils.defaultString(own.getPrompt()));

        weightField = new IntegerField(loc.getValue(J_L.LABEL_WEIGHT));
        weightField.setMin(0);
        weightField.setStepButtonsVisible(true);
        weightField.setValue(own.getWeight());
        weightField.setPlaceholder(builtIn == null ? "1" : String.valueOf(builtIn.weight()));
        weightField.setHelperText(loc.getValue(J_L.HELP_WEIGHT));

        deliverySelect = new Select<>();
        deliverySelect.setLabel(loc.getValue(J_L.LABEL_DELIVERY));
        deliverySelect.setItems(Delivery.values());
        deliverySelect.setItemLabelGenerator(delivery -> delivery == null ? "" : delivery.label());
        deliverySelect.setEmptySelectionAllowed(true);
        deliverySelect.setEmptySelectionCaption(builtIn == null ? Delivery.NEUTRAL.label() : builtIn.delivery().label());
        deliverySelect.setValue(own.getDelivery());

        HorizontalLayout numbers = new HorizontalLayout(weightField, deliverySelect);
        numbers.setPadding(false);
        numbers.setWrap(true);

        VerticalLayout content = new VerticalLayout(names, promptArea, numbers);
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
                if (personaService.mood(newKey) != null) {
                    keyField.setErrorMessage(loc.getValue(J_L.MSG_KEY_TAKEN));
                    keyField.setInvalid(true);
                    return;
                }
            }
            MoodSetting setting = new MoodSetting(newKey);
            setting.setName(StringUtils.defaultIfBlank(nameField.getValue().strip(), null));
            setting.setPrompt(StringUtils.defaultIfBlank(promptArea.getValue().strip(), null));
            setting.setWeight(weightField.getValue());
            setting.setDelivery(deliverySelect.getValue());
            personaService.saveMood(setting);
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

    /** The prompt, for tests. */
    public TextAreaPopoverComponent getPromptArea() {
        return promptArea;
    }

    /** The weight, for tests. */
    public IntegerField getWeightField() {
        return weightField;
    }

    /** Saves as the save button does, for tests. */
    public void clickSave() {
        save();
    }
}
