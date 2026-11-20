package com.github.enerccio.joi.protocol.node;

/**
 * {@code part_kept} (since 1.5): the server kept a part of her body in the state the node asked for with a {@code part}
 * that had an id ({@code ref} is that id).
 *
 * @param part  the part's id
 * @param state the state's id, as kept
 * @param told  true when her reaction to it is still to come (a {@code turn} with the cause {@code part_switched}
 *              follows): a node that shows the change with her reaction waits for it. False when she was told already
 *              (she decided on it, or it was a request she answered), or is not told at all (she has not chatted, or has
 *              no chat model): the change can be shown at once.
 */
public record PartKept(String part, String state, boolean told) {
}
