package com.github.enerccio.joi.domain.chat.turn;

/**
 * What the turn was asked for.
 *
 * @param text   what the user said
 * @param node   the node the words came from, null for none
 * @param speak  whether to speak the reply (when she has a voice)
 * @param pieces speak in pieces as they are rendered instead of the whole reply at once
 */
public record TurnInput(String text, String node, boolean speak, boolean pieces) {
}
