package com.github.enerccio.joi.domain.chat.turn;

import java.util.List;

/**
 * What the turn was asked for.
 *
 * @param text      what the user said, or the event as she is told it; may be empty when {@code images} has some
 * @param node      the node the words came from, null for none
 * @param speak     whether to speak the reply (when she has a voice)
 * @param pieces    speak in pieces as they are rendered instead of the whole reply at once
 * @param eventKind the kind of the event the turn answers ({@code node.first_boot}, {@code node.connected}...), null
 *                  for the user's words; stored as an event message
 * @param images    the uuids of the images the user sent with the words (see {@code ChatImageService}), empty for none; never for an event
 */
public record TurnInput(String text, String node, boolean speak, boolean pieces, String eventKind, List<String> images, String profile) {

    public TurnInput {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** What an event or a message without images asks for. */
    public TurnInput(String text, String node, boolean speak, boolean pieces, String eventKind, List<String> images) {
        this(text, node, speak, pieces, eventKind, images, null);
    }

    public TurnInput(String text, String node, boolean speak, boolean pieces, String eventKind) {
        this(text, node, speak, pieces, eventKind, List.of(), null);
    }

    /** The same, from a node in this profile (see {@code ProfileService}); null for the default one. */
    public TurnInput withProfile(String profile) {
        return new TurnInput(text, node, speak, pieces, eventKind, images, profile);
    }

    /** The user's words. */
    public TurnInput(String text, String node, boolean speak, boolean pieces) {
        this(text, node, speak, pieces, null, List.of(), null);
    }

    /** Whether the turn answers an event rather than the user's words. */
    public boolean isEvent() {
        return eventKind != null;
    }
}
