package com.github.enerccio.joi.defaults;

import com.github.enerccio.joi.tts.text.Delivery;

/**
 * A built-in mood: how the assistant is today, within her personality.
 *
 * @param key      the stable id the user's settings name it by ({@code grumpy})
 * @param name     the name for people
 * @param prompt   the fragment that goes into the system prompt, unless the personality has its own expression of it
 * @param weight   its weight in the daily roll (0: never rolled, only set by hand or by her)
 * @param delivery how the voice reads in it (the mood voice of the voice actor, the emotion of Higgs)
 */
public record MoodDefault(String key, String name, String prompt, int weight, Delivery delivery) {
}
