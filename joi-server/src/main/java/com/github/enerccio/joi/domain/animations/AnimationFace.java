package com.github.enerccio.joi.domain.animations;

import java.util.List;

/**
 * The face an animation may force while it plays (see {@code DESIGN.md} → Bodies, her face): how her eyes are and the
 * expression she shows. Both are keys the renderer page knows; anything else is left out, so a file or a request can't make
 * the page get a value it does not.
 */
public final class AnimationFace {

    /** The eyes an animation can set (anything else, and {@code auto}, leaves them to her state and mood). */
    public static final List<String> EYES = List.of("closed", "half", "wide");

    /** The expressions an animation can show (the VRM presets of the emotions). */
    public static final List<String> EXPRESSIONS = List.of("happy", "sad", "angry", "relaxed", "surprised");

    private AnimationFace() {
    }

    /** The eyes as an animation keeps them: one of {@link #EYES}, else null. */
    public static String eyes(String value) {
        return value != null && EYES.contains(value) ? value : null;
    }

    /** The expression as an animation keeps it: one of {@link #EXPRESSIONS}, else null. */
    public static String expression(String value) {
        return value != null && EXPRESSIONS.contains(value) ? value : null;
    }
}
