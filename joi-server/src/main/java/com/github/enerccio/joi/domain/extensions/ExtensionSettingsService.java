package com.github.enerccio.joi.domain.extensions;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionSettings;

import java.util.Map;
import java.util.Set;

/**
 * The settings of Joi's extensions ({@link ExtensionSettings}) in the places of the chain: kept as a {@code ToolMetadata} under the key
 * {@code ext:<symbolic name>} (the texts in its {@code config}, the secrets protected in its {@code secrets}), so they need no table of
 * their own and are checked as a tool's settings are: the administrator's are written by administrators, the user's by that user, the
 * assistant's by her owner. Only the keys the extension declared are kept.
 */
public interface ExtensionSettingsService {

    /** What is set in one place: the texts, and which secrets are set (never their values). */
    record Place(Map<String, String> values, Set<String> secrets) {
    }

    /** The key of the settings of an extension. */
    static String key(String owner) {
        return "ext:" + owner;
    }

    /** The settings the extension declared, null when it declared none (or is not loaded). */
    ExtensionSettings declared(String owner);

    /** The settings in force for the assistant (null: for the user): each value from the first place that sets it. */
    ExtensionConfig resolve(Joi joi, String owner) throws Exception;

    /** What is set in the place ({@code joi} only for {@link ToolScope#JOI}). */
    Place place(ToolScope scope, Joi joi, String owner) throws Exception;

    /**
     * Writes the place. A blank text removes it. A secret that is null (or missing) is kept as it is, a blank one is removed, any other
     * replaces it. Keys the extension did not declare are refused.
     */
    void save(ToolScope scope, Joi joi, String owner, Map<String, String> values, Map<String, String> secrets) throws Exception;

    /** Removes all the settings of the extension in the place. */
    void clear(ToolScope scope, Joi joi, String owner) throws Exception;
}
