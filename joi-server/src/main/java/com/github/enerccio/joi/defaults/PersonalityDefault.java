package com.github.enerccio.joi.defaults;

import java.util.Map;

/**
 * A built-in personality: who the assistant is, as a prompt fragment, and how some moods sound in it (a tsundere's
 * "happy" is not a cheerful one's). A mood that has no expression here uses the mood's own text.
 *
 * @param key             the stable id the user's settings name it by ({@code tsundere})
 * @param name            the name for people
 * @param prompt          the fragment that goes into the system prompt
 * @param moodExpressions mood key to how that mood sounds in this personality
 */
public record PersonalityDefault(String key, String name, String prompt, Map<String, String> moodExpressions) {

    public PersonalityDefault {
        moodExpressions = moodExpressions == null ? Map.of() : Map.copyOf(moodExpressions);
    }
}
