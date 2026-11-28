package com.github.enerccio.joi.extensions;

import java.util.List;

/**
 * The settings an extension asks for (an address, a key...), declared with {@link JoiExtensionContext#declareSettings}. The
 * administrator sets them for everybody (Administration → Tools), a user for their own assistants (User Settings → Tools); each value
 * is the user's when they set it, else the administrator's (the assistant's own place is read first, for a later page of her dialog).
 * They are kept with the settings of the server's tools, so they stay when the extension is unloaded and are there again when it is
 * loaded.
 *
 * @param caption     the heading of its section on the Tools pages
 * @param description what they are for, a line or two, null for none
 * @param fields      the settings, in the order they are shown
 */
public record ExtensionSettings(String caption, String description, List<ExtensionSetting> fields) {

    public ExtensionSettings {
        if (caption == null || caption.isBlank()) {
            throw new IllegalArgumentException("The settings need a caption");
        }
        fields = List.copyOf(fields);
        if (fields.stream().map(ExtensionSetting::key).distinct().count() != fields.size()) {
            throw new IllegalArgumentException("Two settings have the same key");
        }
    }

    /** The setting of the key, null for none. */
    public ExtensionSetting field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst().orElse(null);
    }
}
