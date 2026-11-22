package com.github.enerccio.joi.protocol.node;

/**
 * {@code mood} (since 1.10): her mood now and how it shows on her face. Sent when a node has sent its manifest, and each
 * time her mood changes. A node shows the face as a soft expression on her body (an animation's own face and her state, asleep,
 * win over it) or ignores the frame.
 *
 * @param mood       the key of the mood, null before her first chat of the day
 * @param name       its name, for a node that shows it
 * @param expression {@code happy}, {@code sad}, {@code angry}, {@code relaxed} or {@code surprised}; null for none
 * @param weight     how strong the expression is, 0 to 1; null for none
 * @param eyes       {@code half} or {@code wide}; null for open and blinking
 */
public record MoodState(String mood, String name, String expression, Double weight, String eyes) {
}
