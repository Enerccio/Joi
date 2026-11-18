package com.github.enerccio.joi.ui.tts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.model.Role;
import com.github.enerccio.joi.tts.text.Delivery;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;

import java.util.*;
import java.util.function.Consumer;

/**
 * Edits a {@link Role}: its voice, and the voices for a mood (a whisper) or a language (a Japanese clip). Copied from Marginalia's
 * audiobook plugin.
 */
public class RoleEditor extends VerticalLayout {

    /** The languages a voice of its own can be given for (the code is what the role stores). */
    private static final List<String> LANGUAGES = List.of("ja", "zh", "ko", "ru", "el", "he", "ar", "hi", "cs", "sk", "de", "fr", "es", "it");

    private final Localization loc;
    private final VoiceChoices choices;
    private final VoiceField voice;
    private final VerticalLayout rows = new VerticalLayout();
    private final List<Row> variantRows = new ArrayList<>();

    public RoleEditor(Role role, VoiceChoices choices, Localization loc) {
        this.loc = loc;
        this.choices = choices;
        setPadding(false);
        setSpacing(false);
        voice = new VoiceField(loc.getValue(J_L.LABEL_VOICE), choices, loc);
        voice.setValue(role.getVoice());
        rows.setPadding(false);
        rows.setSpacing(false);
        role.getVariants().forEach(this::addRow);
        Button add = new Button(loc.getValue(J_L.LABEL_VOICE_FOR_MOOD_OR_LANGUAGE), Solid.PLUS.create(), e -> addRow(nextKey(), ""));
        add.setThemeName("tertiary small");
        add(voice, rows, add);
    }

    /** Tells the listener the main voice whenever the user changes it ({@code clip:<uuid>}, {@code predefined:...} or blank). */
    public void onVoiceChange(Consumer<String> listener) {
        voice.addValueChangeListener(e -> listener.accept(e.getValue()));
    }

    /** The main voice as the editor shows it. */
    public String getVoice() {
        return voice.getValue().strip();
    }

    /** Sets the main voice ({@code clip:<uuid>}, {@code predefined:...} or blank). */
    public void setVoice(String value) {
        voice.setValue(value == null ? "" : value);
    }

    /** Writes what the editor shows into the role. */
    public void read(Role target) {
        target.setVoice(getVoice());
        Map<String, String> variants = new LinkedHashMap<>();
        for (Row row : variantRows) {
            String value = row.voice.getValue().strip();
            if (row.key.getValue() != null && !value.isEmpty()) {
                variants.put(row.key.getValue(), value);
            }
        }
        target.setVariants(variants);
    }

    private static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Delivery delivery : Delivery.values()) {
            if (delivery != Delivery.NEUTRAL) {
                keys.add(Role.moodKey(delivery.name()));
            }
        }
        LANGUAGES.forEach(code -> keys.add(Role.languageKey(code)));
        return keys;
    }

    private String keyLabel(String key) {
        if (Role.isMoodKey(key)) {
            return loc.getValue(J_L.LABEL_MOOD_NAME).formatted(key.substring("mood:".length()).toLowerCase(Locale.ROOT));
        }
        String code = key.substring("lang:".length());
        return loc.getValue(J_L.LABEL_LANGUAGE_NAME).formatted(Locale.forLanguageTag(code).getDisplayLanguage() + " (" + code + ")");
    }

    private String nextKey() {
        List<String> used = variantRows.stream().map(r -> r.key.getValue()).toList();
        return keys().stream().filter(k -> !used.contains(k)).findFirst().orElse(keys().getFirst());
    }

    private void addRow(String key, String voiceText) {
        Row row = new Row(key, voiceText);
        variantRows.add(row);
        rows.add(row.layout);
    }

    private final class Row {
        private final Select<String> key = new Select<>();
        private final VoiceField voice = new VoiceField(loc.getValue(J_L.LABEL_VOICE), choices, loc);
        private HorizontalLayout layout;

        Row(String keyValue, String voiceText) {
            List<String> items = keys();
            if (!items.contains(keyValue)) {
                items.add(keyValue);
            }
            key.setLabel(loc.getValue(J_L.LABEL_WHEN));
            key.setItems(items);
            key.setItemLabelGenerator(RoleEditor.this::keyLabel);
            key.setValue(keyValue);
            voice.setValue(voiceText);
            Button remove = new Button(Solid.TRASH.create(), e -> {
                variantRows.remove(this);
                rows.remove(this.layout);
            });
            remove.setThemeName("tertiary icon");
            remove.setTooltipText(loc.getValue(L.LABEL_REMOVE));
            layout = new HorizontalLayout(key, voice, remove);
            layout.setAlignItems(FlexComponent.Alignment.END);
            layout.setWrap(true);
        }
    }
}
