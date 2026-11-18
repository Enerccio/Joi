package com.github.enerccio.joi.domain.persona;

import java.util.Map;

/**
 * A personality as it is used: the built-in one with what the user changed, or the user's own.
 *
 * @param builtIn         true when it is one of the built-in ones (it can be reset, not deleted)
 * @param changed         true when the user changed a built-in one
 * @param moodExpressions mood key to how the mood sounds in it
 */
public record Personality(String key, String name, String prompt, Map<String, String> moodExpressions, boolean builtIn,
                          boolean changed) {
}
