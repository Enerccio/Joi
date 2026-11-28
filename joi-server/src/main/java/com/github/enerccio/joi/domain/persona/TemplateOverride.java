package com.github.enerccio.joi.domain.persona;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import org.apache.commons.lang3.StringUtils;

/**
 * Whether a template of a link of the chain overrides its parent with a different text. The parent of the user setting is
 * the built-in default; the parent of an assistant is the user setting when it sets the template, else the default (see
 * {@link JoiPromptField#resolve}). A blank text is not set: a link that does not set the template, or a parent that has
 * none, never differs.
 */
public final class TemplateOverride {

    private TemplateOverride() {
    }

    /** What an empty template of the user setting stands for: the built-in default. */
    public static String parentOfUser(JoiPromptField field, JoiDefaults defaults) {
        return field.fallback(defaults);
    }

    /** What an empty template of an assistant stands for: the user setting's, else the built-in default. */
    public static String parentOfAssistant(JoiPromptField field, JoiUserSetting setting, JoiDefaults defaults) {
        return field.resolve((String) null, setting, defaults);
    }

    /**
     * Whether {@code own} is set and differs from a set {@code parent}. The ends of lines (CRLF or LF) and the white space
     * around the whole text do not count: the browser may send either, and the text is the same to the model.
     */
    public static boolean differs(String own, String parent) {
        if (StringUtils.isBlank(own) || StringUtils.isBlank(parent)) {
            return false;
        }
        return !normalize(own).equals(normalize(parent));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
