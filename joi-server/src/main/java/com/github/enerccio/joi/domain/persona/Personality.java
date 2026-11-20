package com.github.enerccio.joi.domain.persona;

import java.util.Map;

/**
 * A personality as it is used: the built-in one with what the user changed, or the user's own.
 *
 * @param builtIn         true when it is one of the built-in ones (it can be reset, not deleted)
 * @param changed         true when the user changed a built-in one
 * @param moodExpressions mood key to how the mood sounds in it
 * @param partConsent     what she does when the user asks to change a part of her body: blank for nothing, the change is
 *                        made; a text lets her decide, and is what she is told about how (see {@code PART_REQUEST_EVENT})
 */
public record Personality(String key, String name, String prompt, Map<String, String> moodExpressions, String partConsent,
                          boolean builtIn, boolean changed) {

    /** Whether she decides on the changes of her parts. */
    public boolean decidesOnParts() {
        return partConsent != null && !partConsent.isBlank();
    }
}
