package com.github.enerccio.joi.extensions;

import java.util.regex.Pattern;

/**
 * One setting of an extension (see {@link ExtensionSettings}): a text, or a secret (a key, a token) that is kept protected as the
 * server's own tools keep theirs, never shown again, and given to the extension in the clear only.
 *
 * @param key         the name it is stored by: never change it ({@code [a-zA-Z0-9_.-]}, at most 64)
 * @param label       the label of its field
 * @param description a line under the field, null for none
 * @param secret      whether it is a secret
 */
public record ExtensionSetting(String key, String label, String description, boolean secret) {

    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");

    public ExtensionSetting {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("The key of a setting is [a-zA-Z0-9_.-], at most 64: " + key);
        }
        label = label == null || label.isBlank() ? key : label.strip();
        description = description == null || description.isBlank() ? null : description.strip();
    }

    /** A text setting. */
    public static ExtensionSetting text(String key, String label, String description) {
        return new ExtensionSetting(key, label, description, false);
    }

    /** A secret setting. */
    public static ExtensionSetting secret(String key, String label, String description) {
        return new ExtensionSetting(key, label, description, true);
    }
}
