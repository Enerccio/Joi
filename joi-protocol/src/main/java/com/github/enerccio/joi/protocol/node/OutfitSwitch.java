package com.github.enerccio.joi.protocol.node;

/**
 * {@code outfit} (since 1.12): the user put an outfit of her body on, on the node. The server keeps it in the profile of the
 * node and tells her, as it does a {@link PartSwitch}; every node of the profile reads her body again ({@code event} {@code body}).
 * An outfit the body does not have is answered by {@code error} ({@code bad_message}) referring to the frame's id.
 *
 * @param outfit the outfit's id ({@code BodyOutfit.id})
 */
public record OutfitSwitch(String outfit) {
}
