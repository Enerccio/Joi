package com.github.enerccio.joi.ui.tts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.http.Http;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.google.gson.Gson;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.server.StreamResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Creates or edits a voice {@link Actor}: its name and tagline, its voice (a clip or a voice of the server), the voices for a mood or a
 * language, and the words spoken in the clip of its voice. <i>Speak</i> tries the voice as it is in the form with one of the TTS models.
 * Stored by <i>Save</i>. Modelled after the actor dialog of Marginalia's audiobook plugin.
 */
@Configurable
public class ActorDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private TtsService ttsService;

    private final Actor actor;
    private Runnable onSave = () -> {
    };

    private TextField nameField;
    private TextField taglineField;
    private TextArea descriptionArea;
    private RoleEditor role;
    private TextArea clipTextArea;
    private String clipOfText;
    private Select<TtsModel> tryModel;
    private TextField sampleText;
    private Button speakButton;
    private final Element audio = new Element("audio");

    /** A new actor. */
    public ActorDialog() {
        this(null);
    }

    /**
     * @param actor the actor to edit (a copy is edited), null for a new one
     */
    public ActorDialog(Actor actor) {
        Gson gson = new Gson();
        this.actor = actor == null ? new Actor() : gson.fromJson(gson.toJson(actor), Actor.class);
    }

    public void setOnSave(Runnable onSave) {
        this.onSave = onSave == null ? () -> {
        } : onSave;
    }

    public void create() throws Exception {
        setHeaderTitle(loc.getValue(J_L.LABEL_ACTOR));
        setWidth("min(52em, 95vw)");
        setCloseOnOutsideClick(false);

        nameField = new TextField(loc.getValue(L.LABEL_NAME));
        nameField.setWidth("20em");
        nameField.setValue(actor.getName());
        taglineField = new TextField(loc.getValue(J_L.LABEL_TAGLINE));
        taglineField.setWidth("20em");
        taglineField.setValue(actor.getTagline());
        taglineField.setPlaceholder(loc.getValue(J_L.PLACEHOLDER_TAGLINE));
        HorizontalLayout names = new HorizontalLayout(nameField, taglineField);
        names.setPadding(false);
        names.setWrap(true);

        descriptionArea = new TextArea(loc.getValue(L.LABEL_DESCRIPTION));
        descriptionArea.setWidthFull();
        descriptionArea.setValue(actor.getDescription());
        descriptionArea.setHelperText(loc.getValue(J_L.HELP_ACTOR_DESCRIPTION));
        com.github.enerccio.marginalia.ui.widgets.ResizableTextArea.install(loc, descriptionArea, "80px");

        Span help = new Span(loc.getValue(J_L.HELP_ACTOR));
        help.addClassName(com.github.enerccio.marginalia.CssStyles.HINT_TEXT);

        role = new RoleEditor(actor, new VoiceChoices(ttsService), loc);

        // the words of the clip of the main voice (Higgs can be given them with the clip)
        clipTextArea = new TextArea(loc.getValue(J_L.LABEL_CLIP_TEXT));
        clipTextArea.setWidthFull();
        com.github.enerccio.marginalia.ui.widgets.ResizableTextArea.install(loc, clipTextArea, "100px");
        showClipText(actor.getVoice());
        role.onVoiceChange(this::showClipText);

        VerticalLayout content = new VerticalLayout(names, descriptionArea, help, role, clipTextArea, createTry());
        content.setPadding(false);
        add(content);

        Button cancelButton = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());
        Button saveButton = new Button(loc.getValue(L.LABEL_SAVE), e -> save());
        saveButton.setThemeName("primary");
        getFooter().add(cancelButton, saveButton);
        DialogFocus.onOpen(this, nameField);
        DialogShortcuts.submitWith(saveButton, nameField, taglineField, descriptionArea);
    }

    private VerticalLayout createTry() throws Exception {
        List<TtsModel> models = ttsService.models();
        tryModel = new Select<>();
        tryModel.setLabel(loc.getValue(J_L.LABEL_TRY_WITH));
        tryModel.setItems(models);
        tryModel.setItemLabelGenerator(TtsModel::getName);
        if (!models.isEmpty()) {
            tryModel.setValue(models.getFirst());
        }
        sampleText = new TextField(loc.getValue(J_L.LABEL_SAMPLE_TEXT));
        sampleText.setPlaceholder(loc.getValue(J_L.PLACEHOLDER_SAMPLE_TEXT));
        sampleText.setWidth("24em");
        speakButton = new Button(loc.getValue(J_L.LABEL_SPEAK), Solid.PLAY.create(), e -> speak());
        speakButton.getElement().appendChild(audio);

        HorizontalLayout row = new HorizontalLayout(tryModel, sampleText, speakButton);
        row.setAlignItems(FlexComponent.Alignment.END);
        row.setWrap(true);
        row.setPadding(false);
        VerticalLayout section = new VerticalLayout(new H4(loc.getValue(J_L.LABEL_TRY_VOICE)), row);
        section.setPadding(false);
        section.setSpacing(false);
        return section;
    }

    /** Shows the words of the clip when the main voice is a clip, and keeps what was typed for the clip shown before. */
    private void showClipText(String voice) {
        String clip = voice != null && voice.startsWith("clip:") ? voice.substring("clip:".length()) : null;
        clipOfText = clip;
        clipTextArea.setVisible(clip != null);
        try {
            String text = clip == null ? null : ttsService.clipText(clip);
            clipTextArea.setValue(text == null ? "" : text);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void speak() {
        TtsModel model = tryModel.getValue();
        if (model == null) {
            Notification.warning(loc.getValue(J_L.MSG_NO_TTS_MODEL));
            return;
        }
        String voice = role.getVoice();
        String text = sampleText.getValue().isBlank() ? loc.getValue(J_L.PLACEHOLDER_SAMPLE_TEXT) : sampleText.getValue();
        UI ui = UI.getCurrent();
        speakButton.setEnabled(false);
        try {
            ttsService.speak(model, voice, text).whenComplete((bytes, error) -> ui.access(() -> {
                speakButton.setEnabled(true);
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    Notification.error(loc.getValue(J_L.MSG_SPEECH_FAILED).formatted(Http.describe(cause)));
                    return;
                }
                play(bytes);
            }));
        } catch (IllegalArgumentException e) {
            speakButton.setEnabled(true);
            Notification.error(loc.getValue(J_L.MSG_SPEECH_FAILED).formatted(e.getMessage()));
        } catch (Exception e) {
            speakButton.setEnabled(true);
            UIUtils.internalServerError(loc, e);
        }
    }

    private void play(byte[] bytes) {
        AudioKind kind = AudioKind.of(bytes);
        StreamResource resource = new StreamResource("voice-" + System.nanoTime() + "." + kind.extension(), () -> new ByteArrayInputStream(bytes));
        resource.setContentType(kind.mime());
        audio.setAttribute("src", resource);
        audio.callJsFunction("play");
    }

    private void save() {
        actor.setName(nameField.getValue().isBlank() ? loc.getValue(J_L.LABEL_NEW_ACTOR) : nameField.getValue().strip());
        actor.setTagline(taglineField.getValue().strip());
        actor.setDescription(descriptionArea.getValue().strip());
        role.read(actor);
        try {
            ttsService.saveActor(actor);
            if (clipOfText != null) {
                String stored = ttsService.clipText(clipOfText);
                if (!clipTextArea.getValue().strip().equals(stored == null ? "" : stored)) {
                    ttsService.saveClipText(clipOfText, clipTextArea.getValue());
                }
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        onSave.run();
        close();
    }

    /** The name field, for tests. */
    public TextField getNameField() {
        return nameField;
    }

    /** The editor of the voices, for tests. */
    public RoleEditor getRoleEditor() {
        return role;
    }

    /** Saves as the save button does, for tests. */
    public void clickSave() {
        save();
    }
}
