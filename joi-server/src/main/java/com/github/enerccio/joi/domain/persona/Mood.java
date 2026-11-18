package com.github.enerccio.joi.domain.persona;

import com.github.enerccio.joi.tts.text.Delivery;

/**
 * A mood as it is used: the built-in one with what the user changed, or the user's own.
 *
 * @param builtIn true when it is one of the built-in ones (it can be reset, not deleted)
 * @param changed true when the user changed a built-in one
 */
public record Mood(String key, String name, String prompt, int weight, Delivery delivery, boolean builtIn, boolean changed) {
}
