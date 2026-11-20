package com.github.enerccio.joi.protocol.node;

/**
 * {@code say}: what the user said at the node. The frame's id is the turn: her answer comes in frames that refer to
 * it ({@code reasoning}, {@code text}, {@code tool}, {@code reply}, {@code audio}, then {@code done} or
 * {@code error}). Turns of one assistant run one at a time.
 *
 * @param text      the words of the user
 * @param speak     whether to speak the reply too, in {@code audio} frames (default true; ignored when she has no voice)
 * @param reasoning whether to send the thinking of the model (default false)
 * @param withEvents whether to take along what waits to be told to her (since 1.5: the changes of her parts the node just
 *                   sent): her turn is about the words and those events together, and this frame's id is its id (default false)
 */
public record Say(String text, Boolean speak, Boolean reasoning, Boolean withEvents) {

    public Say(String text, Boolean speak, Boolean reasoning) {
        this(text, speak, reasoning, null);
    }

    public boolean withEventsOrDefault() {
        return withEvents != null && withEvents;
    }

    public boolean speakOrDefault() {
        return speak == null || speak;
    }

    public boolean reasoningOrDefault() {
        return reasoning != null && reasoning;
    }
}
