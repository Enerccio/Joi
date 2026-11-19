package com.github.enerccio.joi.protocol.node;

/**
 * {@code say}: what the user said at the node. The frame's id is the turn: her answer comes in frames that refer to
 * it ({@code reasoning}, {@code text}, {@code tool}, {@code reply}, {@code audio}, then {@code done} or
 * {@code error}). Turns of one assistant run one at a time.
 *
 * @param text      the words of the user
 * @param speak     whether to speak the reply too, in {@code audio} frames (default true; ignored when she has no voice)
 * @param reasoning whether to send the thinking of the model (default false)
 */
public record Say(String text, Boolean speak, Boolean reasoning) {

    public boolean speakOrDefault() {
        return speak == null || speak;
    }

    public boolean reasoningOrDefault() {
        return reasoning != null && reasoning;
    }
}
