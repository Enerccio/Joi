package com.github.enerccio.joi.protocol.node;

/**
 * {@code expression} (since 1.15): she shows an expression on her face now (her {@code set_expression} function), for a few
 * seconds, over her mood's face. The node fades it in, holds it and fades it out; an expression her model does not have is
 * ignored, and a null expression ends the one shown. A node may ignore the frame.
 *
 * @param expression the name of the expression of her model (a VRM preset such as {@code happy}, or one of the model's own);
 *                   null ends the one shown
 * @param weight     how strong it is, 0 to 1; null for {@link #DEFAULT_WEIGHT}
 * @param seconds    how long it is held, at most {@link #MAX_SECONDS}; null for {@link #DEFAULT_SECONDS}
 */
public record ShowExpression(String expression, Double weight, Double seconds) {

    /** The weight of an expression that gives none. */
    public static final double DEFAULT_WEIGHT = 0.8;
    /** How long an expression that says nothing is held. */
    public static final double DEFAULT_SECONDS = 4;
    /** The longest an expression is held. */
    public static final double MAX_SECONDS = 60;
}
