package com.github.enerccio.joi.extensions;

import com.github.enerccio.joi.domain.tools.ToolScope;

import java.util.Map;

/**
 * The settings of an extension as they are in force for an assistant ({@link JoiExtensionContext#settings}): each value from the first
 * place of the chain that sets it (hers, the user's, the administrator's). Secrets are in the clear: keep them in the extension.
 *
 * @param values the values by key; a setting nobody set is missing
 * @param from   where each value comes from
 */
public record ExtensionConfig(Map<String, String> values, Map<String, ToolScope> from) {

    public ExtensionConfig {
        values = Map.copyOf(values);
        from = Map.copyOf(from);
    }

    /** No settings. */
    public static ExtensionConfig empty() {
        return new ExtensionConfig(Map.of(), Map.of());
    }

    /** The value of the setting, null when nobody set it. */
    public String value(String key) {
        return values.get(key);
    }

    /** Whether the setting is set. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    @Override
    public String toString() {
        // never the values: some are secrets
        return "ExtensionConfig" + values.keySet();
    }
}
