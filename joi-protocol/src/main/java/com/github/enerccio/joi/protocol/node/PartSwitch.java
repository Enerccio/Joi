package com.github.enerccio.joi.protocol.node;

/**
 * {@code part} (since 1.5): the user put a part of her body in another state on the node (took the visor off). The server
 * keeps it on the body (it is still so at the next start, and on every other node) and starts her turn about it
 * ({@code turn} with the cause {@code part_switched}), told with the state's description, so she can react. The frame
 * may carry an {@code id}: the server then answers with {@code part_kept} (this record again, as kept) once it is kept,
 * or with {@code error}, both referring to the id.
 *
 * @param part  the part's id ({@code BodyPart.id})
 * @param state the state's id ({@code BodyPartState.id})
 */
public record PartSwitch(String part, String state) {
}
