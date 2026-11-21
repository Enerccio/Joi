package com.github.enerccio.joi.ui.tts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.TtsService.Clip;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.TtsLog;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a voice: one of the reference clips of the user (an audio resource; the button adds one) or the file name of a voice of the
 * speech server typed in ({@code Henry.wav}). The value is the text form of a voice, {@code clip:<uuid>} or {@code predefined:<file>},
 * empty for none. Copied from Marginalia's audiobook plugin, without its preview button (the actor dialog has one).
 */
public class VoiceField extends CustomField<String> {

    /** A voice clip is a few seconds of speech: kept in memory while it is read, so its size is capped. */
    static final int MAX_CLIP_BYTES = 20 * 1024 * 1024;

    private final VoiceChoices choices;
    private final ComboBox<String> box = new ComboBox<>();
    private final Runnable reload = this::reloadItems;
    private boolean updating;

    public VoiceField(String label, VoiceChoices choices, Localization loc) {
        this.choices = choices;
        setLabel(label);

        box.setAllowCustomValue(true);
        box.setClearButtonVisible(true);
        box.setWidth("22em");
        box.setPlaceholder(loc.getValue(J_L.PLACEHOLDER_VOICE));
        box.setItemLabelGenerator(choices::label);
        box.addCustomValueSetListener(e -> {
            String typed = e.getDetail().strip();
            if (!typed.isEmpty()) {
                box.setValue(typed.startsWith("clip:") || typed.startsWith("predefined:") ? typed : "predefined:" + typed);
            }
        });
        box.addValueChangeListener(e -> {
            if (!updating) {
                updateValue();
            }
        });

        InMemoryUploadHandler handler = new InMemoryUploadHandler((metadata, data) -> {
            try {
                Clip clip = choices.upload(metadata.fileName(), data, metadata.contentType());
                setValue("clip:" + clip.uuid());
            } catch (Exception e) {
                TtsLog.get().warn("The clip could not be added: {}", e.getMessage(), e);
                Notification.error(loc.getValue(J_L.MSG_CLIP_NOT_ADDED).formatted(e.getMessage()));
            }
        }) {
            @Override
            public long getFileSizeMax() {
                // the server's own limit: the client's setMaxFileSize below only spares the user a useless upload
                return MAX_CLIP_BYTES;
            }
        };
        Upload upload = new Upload(handler);
        upload.setAcceptedFileTypes("audio/wav", "audio/x-wav", "audio/mpeg", ".wav", ".mp3");
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        upload.setMaxFileSize(MAX_CLIP_BYTES);
        upload.setUploadButton(new Button(loc.getValue(J_L.LABEL_ADD_CLIP), Solid.UPLOAD.create()));

        HorizontalLayout row = new HorizontalLayout(box, upload);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setPadding(false);
        add(row);
        reloadItems();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        choices.addListener(reload);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        choices.removeListener(reload);
        super.onDetach(detachEvent);
    }

    private List<String> items(String current) {
        List<String> items = new ArrayList<>();
        choices.clips().forEach(c -> items.add("clip:" + c.uuid()));
        if (current != null && !current.isBlank() && !items.contains(current)) {
            items.add(current);
        }
        return items;
    }

    private void reloadItems() {
        String current = box.getValue();
        updating = true;
        try {
            box.setItems(items(current));
            box.setValue(current);
        } finally {
            updating = false;
        }
    }

    @Override
    protected String generateModelValue() {
        return box.getValue() == null ? "" : box.getValue();
    }

    @Override
    protected void setPresentationValue(String value) {
        String voice = value == null ? "" : value;
        updating = true;
        try {
            box.setItems(items(voice));
            box.setValue(voice.isBlank() ? null : voice);
        } finally {
            updating = false;
        }
    }
}
