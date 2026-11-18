package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.Personality;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.joi.ui.widgets.RoleModels;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The About tab of a {@link JoiDialog}: her name, what she calls the user, her personality, the models of her roles and
 * her voice. Each change is stored at once; a change of her name, the user's name or her personality is also queued as
 * an event for her, once she has chatted ({@link JoiService#saveAbout}). An empty field uses the user's setting, shown
 * greyed out.
 */
@Configurable
public class JoiAboutPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiService joiService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private AIService aiService;

    @Autowired
    private TtsService ttsService;

    private final JoiDialog dialog;

    private TextField nameField;
    private TextField userNameField;
    private Select<Personality> personalitySelect;
    private final Map<TextLLMType, ComboBox<AI>> modelBoxes = new LinkedHashMap<>();
    private Select<TtsModel> ttsModelSelect;
    private Select<Actor> voiceActorSelect;
    /** Her persona as stored last: what a change is compared with. */
    private JoiService.Persona before;
    /** True while {@link #load} fills the fields: their changes are not the user's. */
    private boolean loading;

    public JoiAboutPart(JoiDialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public J_L caption() {
        return J_L.LABEL_ABOUT;
    }

    @Override
    public Component create() {
        nameField = new TextField(loc.getValue(J_L.LABEL_ASSISTANT_NAME));
        userNameField = new TextField(loc.getValue(J_L.LABEL_USER_NAME));
        userNameField.setHelperText(loc.getValue(J_L.HELP_USER_NAME));

        personalitySelect = new Select<>();
        personalitySelect.setLabel(loc.getValue(J_L.LABEL_PERSONALITY));
        personalitySelect.setItemLabelGenerator(p -> p == null ? "" : p.name());
        personalitySelect.setEmptySelectionAllowed(true);
        personalitySelect.setHelperText(loc.getValue(J_L.HELP_PERSONALITY));

        modelBoxes.put(TextLLMType.CHAT, modelBox(J_L.LABEL_CHAT_MODEL));
        modelBoxes.put(TextLLMType.DEEP_REASONING, modelBox(J_L.LABEL_REASONING_MODEL));
        modelBoxes.put(TextLLMType.AUXILIARY, modelBox(J_L.LABEL_AUXILIARY_MODEL));

        ttsModelSelect = new Select<>();
        ttsModelSelect.setLabel(loc.getValue(J_L.LABEL_TTS_MODEL));
        ttsModelSelect.setItemLabelGenerator(model -> model == null ? "" : model.getName());
        ttsModelSelect.setEmptySelectionAllowed(true);

        voiceActorSelect = new Select<>();
        voiceActorSelect.setLabel(loc.getValue(J_L.LABEL_VOICE_ACTOR));
        voiceActorSelect.setItemLabelGenerator(actor -> actor == null ? "" : actor.getName());
        voiceActorSelect.setEmptySelectionAllowed(true);

        Span inherited = new Span(loc.getValue(J_L.HELP_JOI_INHERITED));
        inherited.addClassName(CssStyles.HINT_TEXT);
        Span events = new Span(loc.getValue(J_L.HELP_JOI_PERSONA_EVENTS));
        events.addClassName(CssStyles.HINT_TEXT);

        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));
        form.add(nameField, userNameField, personalitySelect);
        form.setColspan(personalitySelect, 2);
        modelBoxes.values().forEach(form::add);
        // the voice on a row of its own
        form.setColspan(modelBoxes.get(TextLLMType.AUXILIARY), 2);
        form.add(ttsModelSelect, voiceActorSelect);
        form.addClassName(CssStyles.FORM_PADDING_BOTTOM);

        for (HasValue<?, ?> field : fields()) {
            field.addValueChangeListener(e -> {
                if (!loading) {
                    save();
                }
            });
        }

        VerticalLayout layout = new VerticalLayout(inherited, events, form);
        layout.setWidthFull();
        return layout;
    }

    private ComboBox<AI> modelBox(J_L label) {
        ComboBox<AI> box = new ComboBox<>(loc.getValue(label));
        box.setItemLabelGenerator(AI::getName);
        box.setClearButtonVisible(true);
        box.setHelperText(loc.getValue(J_L.HELP_CHAT_MODEL));
        return box;
    }

    private List<HasValue<?, ?>> fields() {
        List<HasValue<?, ?>> fields = new ArrayList<>(List.of(nameField, userNameField, personalitySelect));
        fields.addAll(modelBoxes.values());
        fields.addAll(List.of(ttsModelSelect, voiceActorSelect));
        return fields;
    }

    /** Puts the cursor into the name when the dialog opens. */
    public void focus(Dialog dialog) {
        DialogFocus.onOpen(dialog, nameField);
    }

    @Override
    public void load(Joi joi) throws Exception {
        loading = true;
        try {
            JoiUserSetting setting = personaService.getUserSetting();
            nameField.setValue(StringUtils.defaultString(joi.getAssistantName()));
            nameField.setPlaceholder(personaService.assistantName());
            userNameField.setValue(StringUtils.defaultString(joi.getUserName()));
            userNameField.setPlaceholder(personaService.userName());

            List<Personality> personalities = personaService.personalities();
            personalitySelect.setItems(personalities);
            Personality inherited = personaService.currentPersonality();
            inheritedChoice(personalitySelect, inherited == null ? "" : inherited.name());
            personalitySelect.setValue(find(personalities, Personality::key, joi.getPersonality()));

            List<AI> ais = aiService.findAllForUser();
            fillModel(TextLLMType.CHAT, ais, joi.getChatModel(), setting.getChatModel());
            fillModel(TextLLMType.DEEP_REASONING, ais, joi.getReasoningModel(), setting.getReasoningModel());
            fillModel(TextLLMType.AUXILIARY, ais, joi.getAuxiliaryModel(), setting.getAuxiliaryModel());

            List<TtsModel> ttsModels = ttsService.models();
            ttsModelSelect.setItems(ttsModels);
            TtsModel userTts = find(ttsModels, TtsModel::getId, setting.getTtsModel());
            inheritedChoice(ttsModelSelect, userTts == null ? "" : userTts.getName());
            ttsModelSelect.setValue(find(ttsModels, TtsModel::getId, joi.getTtsModel()));

            List<Actor> actors = ttsService.actors();
            voiceActorSelect.setItems(actors);
            Actor userActor = find(actors, Actor::getId, setting.getVoiceActor());
            inheritedChoice(voiceActorSelect, userActor == null ? "" : userActor.getName());
            voiceActorSelect.setValue(find(actors, Actor::getId, joi.getVoiceActor()));

            before = joiService.persona(joi);
        } finally {
            loading = false;
        }
    }

    /** No choice of hers: what the user's setting chose, greyed out in the field and named in the list. */
    private static void inheritedChoice(Select<?> select, String inherited) {
        select.setPlaceholder(inherited);
        select.setEmptySelectionCaption(inherited);
    }

    private void fillModel(TextLLMType type, List<AI> ais, Long own, Long ofUser) {
        ComboBox<AI> box = modelBoxes.get(type);
        RoleModels.fill(box, ais, type, own);
        AI inherited = RoleModels.find(ais, ofUser);
        box.setPlaceholder(inherited == null ? "" : inherited.getName());
    }

    private static <T> T find(List<T> items, Function<T, String> id, String wanted) {
        return wanted == null ? null : items.stream().filter(item -> wanted.equals(id.apply(item))).findFirst().orElse(null);
    }

    private void save() {
        Joi joi = dialog.getJoi();
        joi.setAssistantName(StringUtils.defaultIfBlank(nameField.getValue().strip(), null));
        joi.setUserName(StringUtils.defaultIfBlank(userNameField.getValue().strip(), null));
        joi.setPersonality(personalitySelect.getValue() == null ? null : personalitySelect.getValue().key());
        joi.setChatModel(idOf(modelBoxes.get(TextLLMType.CHAT).getValue()));
        joi.setReasoningModel(idOf(modelBoxes.get(TextLLMType.DEEP_REASONING).getValue()));
        joi.setAuxiliaryModel(idOf(modelBoxes.get(TextLLMType.AUXILIARY).getValue()));
        joi.setTtsModel(ttsModelSelect.getValue() == null ? null : ttsModelSelect.getValue().getId());
        joi.setVoiceActor(voiceActorSelect.getValue() == null ? null : voiceActorSelect.getValue().getId());
        dialog.saveAbout(before);
        try {
            before = joiService.persona(dialog.getJoi());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private static Long idOf(AI ai) {
        return ai == null ? null : ai.getId();
    }

    // for tests

    public TextField getNameField() {
        return nameField;
    }

    public TextField getUserNameField() {
        return userNameField;
    }

    public Select<Personality> getPersonalitySelect() {
        return personalitySelect;
    }

    public ComboBox<AI> getModelBox(TextLLMType type) {
        return modelBoxes.get(type);
    }
}
