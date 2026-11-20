package com.github.enerccio.joi.domain.chat.turn;

/**
 * What the turn was asked for.
 *
 * @param text      what the user said, or the event as she is told it
 * @param node      the node the words came from, null for none
 * @param speak     whether to speak the reply (when she has a voice)
 * @param pieces    speak in pieces as they are rendered instead of the whole reply at once
 * @param eventKind the kind of the event the turn answers ({@code node.first_boot}, {@code node.connected}...), null
 *                  for the user's words; stored as an event message
 */
public record TurnInput(String text, String node, boolean speak, boolean pieces, String eventKind) {

    /** The user's words. */
    public TurnInput(String text, String node, boolean speak, boolean pieces) {
        this(text, node, speak, pieces, null);
    }

    /** Whether the turn answers an event rather than the user's words. */
    public boolean isEvent() {
        return eventKind != null;
    }
}
