package com.github.enerccio.joi.loc;

import com.github.enerccio.marginalia.loc.LocalizationKey;

/**
 * The keys of Joi's UI texts, on top of the platform's {@code L}. The texts are in the {@code JoiLocalizationBundle*}
 * classes, one per language, registered on the {@code localization} bean. Grouped by prefix; a new key goes at the end
 * of its group here and in every bundle.
 */
public enum J_L implements LocalizationKey {

    // --- LABEL ---
    LABEL_APP_DESC,
    LABEL_LOGOUT,
    LABEL_MODELS,
    LABEL_ADD_MODEL,
    LABEL_SHARED,
    LABEL_CLONE_MODEL,
}
