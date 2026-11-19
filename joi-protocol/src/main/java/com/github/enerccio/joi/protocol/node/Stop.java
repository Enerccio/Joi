package com.github.enerccio.joi.protocol.node;

/**
 * {@code stop}: stop a turn of this node, the text and the speech not yet sent (the user talked over her). The turn
 * ends with {@code error} {@code stopped}; what was already stored stays.
 *
 * @param turn the id of the {@code say}, null for the turn running now
 */
public record Stop(String turn) {
}
