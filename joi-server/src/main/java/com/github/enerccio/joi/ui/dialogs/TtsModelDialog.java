package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.http.Http;
import com.github.enerccio.joi.tts.model.HiggsPrecision;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.GpuLockSelect;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.google.gson.Gson;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.Map;

/**
 * Creates or edits a {@link TtsModel}: the speech server, the model it must have loaded, its GPU lock and the defaults of the speech.
 * The key is kept protected: a blank field keeps the stored one. <i>Test connection</i> asks the server as the form is, under the
 * lock. Modelled after the backend editor of Marginalia's audiobook plugin, without the requests around a batch (the GPU lock's
 * steps do that).
 */
@Configurable
public class TtsModelDialog extends Dialog {

    private static final Map<String, J_L> MODEL_LABELS = Map.of(
            TtsModel.MODEL_TURBO, J_L.ENUM_TTS_MODEL_TURBO,
            TtsModel.MODEL_ORIGINAL, J_L.ENUM_TTS_MODEL_ORIGINAL,
            TtsModel.MODEL_MULTILINGUAL, J_L.ENUM_TTS_MODEL_MULTILINGUAL,
            TtsModel.MODEL_HIGGS, J_L.ENUM_TTS_MODEL_HIGGS);

    @Autowired
    private Localization loc;

    @Autowired
    private TtsService ttsService;

    private final TtsModel model;
    private Runnable onSave = () -> {
    };

    private TextField nameField;
    private Select<String> modelSelect;
    private TextField addressField;
    private PasswordField keyField;
    private boolean removeKey;
    private Checkbox localBox;
    private GpuLockSelect gpuLock;
    private IntegerField timeoutField;
    private Select<String> formatSelect;
    private NumberField temperatureField;
    private NumberField cfgWeightField;
    private NumberField speedField;
    private IntegerField chunkSizeField;
    private Select<HiggsPrecision> precisionSelect;
    private Checkbox sendClipTextBox;
    private Button testButton;

    /** A new model. */
    public TtsModelDialog() {
        this(null);
    }

    /**
     * @param model the model to edit (a copy is edited), null for a new one
     */
    public TtsModelDialog(TtsModel model) {
        Gson gson = new Gson();
        this.model = model == null ? new TtsModel() : gson.fromJson(gson.toJson(model), TtsModel.class);
    }

    public void setOnSave(Runnable onSave) {
        this.onSave = onSave == null ? () -> {
        } : onSave;
    }

    public void create() {
        setHeaderTitle(loc.getValue(J_L.LABEL_TTS_MODEL));
        setWidth("min(48em, 95vw)");
        setCloseOnOutsideClick(false);

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setValue(model.getName());
        nameField.setRequired(true);

        modelSelect = new Select<>();
        modelSelect.setLabel(loc.getValue(J_L.LABEL_SPEECH_MODEL));
        modelSelect.setItems(TtsModel.MODELS);
        modelSelect.setItemLabelGenerator(id -> loc.getValue(MODEL_LABELS.get(id)));
        modelSelect.setValue(model.getModel());
        modelSelect.setHelperText(loc.getValue(J_L.HELP_SPEECH_MODEL));

        addressField = new TextField(loc.getValue(J_L.LABEL_ADDRESS));
        addressField.setValue(model.getBaseUrl());
        addressField.setHelperText(loc.getValue(J_L.HELP_TTS_ADDRESS));
        addressField.setRequired(true);

        keyField = new PasswordField(loc.getValue(L.LABEL_API_KEY));
        Button removeKeyButton = new Button(Solid.TIMES.create(), e -> {
            removeKey = true;
            keyField.clear();
            keyField.setPlaceholder("");
            e.getSource().setVisible(false);
        });
        removeKeyButton.setThemeName("tertiary icon");
        removeKeyButton.setTooltipText(loc.getValue(L.LABEL_REMOVE));
        boolean hasKey = model.getProtectedApiKey() != null;
        keyField.setPlaceholder(hasKey ? loc.getValue(J_L.PLACEHOLDER_STORED_KEY) : "");
        removeKeyButton.setVisible(hasKey);
        keyField.setSuffixComponent(removeKeyButton);

        localBox = new Checkbox(loc.getValue(J_L.LABEL_SERVER_IS_LOCAL), model.isLocal());
        localBox.setHelperText(loc.getValue(J_L.HELP_SERVER_IS_LOCAL));

        gpuLock = new GpuLockSelect();
        gpuLock.setLockUuid(model.getGpuLock());
        gpuLock.setHelperText(loc.getValue(J_L.HELP_TTS_GPU_LOCK));

        timeoutField = new IntegerField(loc.getValue(J_L.LABEL_TIMEOUT_SECONDS));
        timeoutField.setMin(1);
        timeoutField.setValue(model.getTimeoutSeconds());

        formatSelect = new Select<>();
        formatSelect.setLabel(loc.getValue(J_L.LABEL_AUDIO_FORMAT));
        formatSelect.setItems(TtsModel.FORMATS);
        formatSelect.setValue(model.getFormat());

        temperatureField = numberField(L.LABEL_TEMPERATURE, model.getTemperature(), 0.05, 0.05, 2.0);
        cfgWeightField = numberField(J_L.LABEL_CFG_WEIGHT, model.getCfgWeight(), 0.05, 0.05, 2.0);
        speedField = numberField(J_L.LABEL_SPEED, model.getSpeed(), 0.05, 0.25, 4.0);

        chunkSizeField = new IntegerField(loc.getValue(J_L.LABEL_CHUNK_SIZE));
        chunkSizeField.setMin(TtsModel.MIN_CHUNK_SIZE);
        chunkSizeField.setMax(TtsModel.MAX_CHUNK_SIZE);
        chunkSizeField.setValue(model.getChunkSize());

        precisionSelect = new Select<>();
        precisionSelect.setLabel(loc.getValue(J_L.LABEL_PRECISION));
        precisionSelect.setItems(HiggsPrecision.values());
        precisionSelect.setItemLabelGenerator(HiggsPrecision::wire);
        precisionSelect.setValue(model.getPrecision());
        precisionSelect.setHelperText(loc.getValue(J_L.HELP_PRECISION));

        sendClipTextBox = new Checkbox(loc.getValue(J_L.LABEL_SEND_CLIP_TEXT), model.isSendClipText());
        sendClipTextBox.setHelperText(loc.getValue(J_L.HELP_SEND_CLIP_TEXT));

        modelSelect.addValueChangeListener(e -> showModelFields());
        showModelFields();

        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("32em", 2));
        form.add(nameField, modelSelect, addressField, keyField);
        form.add(localBox, 2);
        form.add(gpuLock, 2);
        form.add(timeoutField, formatSelect, temperatureField, cfgWeightField, speedField, chunkSizeField, precisionSelect);
        form.add(sendClipTextBox, 2);
        add(form);

        testButton = new Button(loc.getValue(L.LABEL_TEST_CONNECTION), Solid.PLUG.create(), e -> test());
        testButton.setTooltipText(loc.getValue(J_L.HELP_TTS_TEST));
        Button cancelButton = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        Button saveButton = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        saveButton.setThemeName("primary");
        getFooter().add(testButton, cancelButton, saveButton);

        DialogFocus.onOpen(this, nameField);
        DialogShortcuts.submitWith(saveButton, nameField, addressField, keyField);
    }

    private NumberField numberField(com.github.enerccio.marginalia.loc.LocalizationKey label, double value, double step, double min,
                                    double max) {
        NumberField field = new NumberField(loc.getValue(label));
        field.setStep(step);
        field.setMin(min);
        field.setMax(max);
        field.setStepButtonsVisible(true);
        field.setValue(value);
        return field;
    }

    /** Higgs has a precision and can be sent the words of a clip; Chatterbox has a chunk size and a cfg weight. */
    private void showModelFields() {
        boolean higgs = TtsModel.MODEL_HIGGS.equals(modelSelect.getValue());
        precisionSelect.setVisible(higgs);
        sendClipTextBox.setVisible(higgs);
        cfgWeightField.setVisible(!higgs);
        chunkSizeField.setVisible(!higgs);
    }

    /** Writes the form into the model; false (and the fields marked) when something required is missing. */
    private boolean read(TtsModel target) {
        boolean valid = true;
        if (nameField.getValue().isBlank()) {
            nameField.setInvalid(true);
            valid = false;
        }
        if (addressField.getValue().isBlank()) {
            addressField.setInvalid(true);
            valid = false;
        }
        if (!valid) {
            return false;
        }
        target.setName(nameField.getValue().strip());
        target.setModel(modelSelect.getValue());
        target.setBaseUrl(addressField.getValue().strip());
        if (!keyField.getValue().isBlank()) {
            target.setProtectedApiKey(ttsService.protect(keyField.getValue().strip()));
        } else if (removeKey) {
            target.setProtectedApiKey(null);
        }
        target.setLocal(localBox.getValue());
        target.setGpuLock(gpuLock.getLockUuid());
        target.setTimeoutSeconds(timeoutField.getValue() == null ? 0 : timeoutField.getValue());
        target.setFormat(formatSelect.getValue());
        target.setTemperature(temperatureField.getValue() == null ? 0 : temperatureField.getValue());
        target.setCfgWeight(cfgWeightField.getValue() == null ? 0 : cfgWeightField.getValue());
        target.setSpeed(speedField.getValue() == null ? 0 : speedField.getValue());
        target.setChunkSize(chunkSizeField.getValue() == null ? 0 : chunkSizeField.getValue());
        target.setPrecision(precisionSelect.getValue());
        target.setSendClipText(sendClipTextBox.getValue());
        return true;
    }

    private void save() {
        if (!read(model)) {
            return;
        }
        try {
            ttsService.saveModel(model);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        onSave.run();
        close();
    }

    private void test() {
        Gson gson = new Gson();
        TtsModel asInForm = gson.fromJson(gson.toJson(model), TtsModel.class);
        if (!read(asInForm)) {
            return;
        }
        UI ui = UI.getCurrent();
        testButton.setEnabled(false);
        ttsService.test(asInForm).whenComplete((result, error) -> ui.access(() -> {
            testButton.setEnabled(true);
            if (error != null) {
                Throwable cause = error.getCause() != null && error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                Notification.error(loc.getValue(J_L.MSG_CONNECTION_FAILED).formatted(Http.describe(cause)));
            } else if (result.modelLoaded()) {
                Notification.success(loc.getValue(J_L.MSG_TTS_MODEL_LOADED).formatted(result.model() == null ? asInForm.getModel() : result.model()));
            } else {
                Notification.warning(loc.getValue(J_L.MSG_TTS_NO_MODEL));
            }
        }));
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The address field, for tests. */
    public TextField getAddressField() {
        return addressField;
    }

    /** The select of the speech model, for tests. */
    public Select<String> getModelSelect() {
        return modelSelect;
    }

    /** Saves as the save button does, for tests. */
    public void clickSave() {
        save();
    }
}
