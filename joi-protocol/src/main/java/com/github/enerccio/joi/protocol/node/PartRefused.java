package com.github.enerccio.joi.protocol.node;

/**
 * {@code part_refused} (since 1.5): she did not allow the change of a part that the node asked for with a {@code part}
 * that had an id ({@code ref} is that id). Her personality may let her decide on such a request. The part stays in the
 * state it is in on the body.
 *
 * @param part   the part's id
 * @param state  the state's id that was asked for
 * @param reason what she said about it, null for no reason
 */
public record PartRefused(String part, String state, String reason) {
}
