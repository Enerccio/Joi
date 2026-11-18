package com.github.enerccio.joi.loc;

import com.github.enerccio.marginalia.loc.Language;
import com.github.enerccio.marginalia.loc.TextBundleBase;

/**
 * Joi's texts in English, the default language: every key of {@link J_L} has one here.
 */
public class JoiLocalizationBundleEN extends TextBundleBase<J_L> {

    public JoiLocalizationBundleEN() {
        super(J_L.class);
    }

    @Override
    public Language getLanguage() {
        return Language.EN;
    }

    @Override
    protected void loadMessages() {
        // --- LABEL ---
        setValue(J_L.LABEL_APP_DESC, "Your personal assistant");
        setValue(J_L.LABEL_LOGOUT, "Log out");

        // --- MSG ---
        setValue(J_L.MSG_SIGNED_IN, "Signed in as %s. Nothing else is here yet.");
    }
}
