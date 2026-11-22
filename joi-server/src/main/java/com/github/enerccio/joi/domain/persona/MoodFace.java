package com.github.enerccio.joi.domain.persona;

import com.github.enerccio.joi.domain.animations.AnimationFace;

import java.util.List;

/**
 * How a mood shows on her face (see {@code DESIGN.md} → Bodies, her face): a soft expression and, for some moods, how her
 * eyes are. The renderer page shows it; an animation's own face and her state (asleep) win over it.
 *
 * @param expression one of {@link AnimationFace#EXPRESSIONS}, null for none
 * @param weight     how strong it is, 0 to 1
 * @param eyes       one of {@link #EYES}, null for open and blinking
 */
public record MoodFace(String expression, double weight, String eyes) {

    /** The eyes a mood can give her (anything else is open and blinking). */
    public static final List<String> EYES = List.of("half", "wide");

    /** What the user's setting says for no expression (a missing value keeps the built-in one). */
    public static final String NONE = "none";

    /** What the user's setting says for open eyes (a missing value keeps the built-in one). */
    public static final String OPEN = "auto";

    /** A face of no expression and open eyes. */
    public static final MoodFace PLAIN = new MoodFace(null, 0, null);

    /** How strong an expression is when the user gave none. */
    public static final double DEFAULT_WEIGHT = 0.5;

    public MoodFace {
        expression = AnimationFace.expression(expression);
        weight = expression == null ? 0 : Math.max(0, Math.min(1, weight));
        eyes = eyes != null && EYES.contains(eyes) ? eyes : null;
    }

    /**
     * The face the user's setting makes of the built-in one: a missing value keeps the built-in one, {@link #NONE} and
     * {@link #OPEN} take it away, a value that is not one of the known ones is ignored.
     */
    public MoodFace changedBy(MoodSetting changes) {
        String expression = this.expression;
        String chosen = changes.getExpression();
        if (NONE.equals(chosen)) {
            expression = null;
        } else if (AnimationFace.expression(chosen) != null) {
            expression = chosen;
        }
        String eyes = this.eyes;
        if (OPEN.equals(changes.getEyes())) {
            eyes = null;
        } else if (changes.getEyes() != null && EYES.contains(changes.getEyes())) {
            eyes = changes.getEyes();
        }
        double weight = changes.getExpressionWeight() != null ? changes.getExpressionWeight()
                : this.expression == null && expression != null ? DEFAULT_WEIGHT : this.weight;
        return new MoodFace(expression, weight, eyes);
    }

    /** Whether the user's setting changes anything of the face. */
    public static boolean changes(MoodSetting setting) {
        return setting.getExpression() != null || setting.getExpressionWeight() != null || setting.getEyes() != null;
    }
}
