package com.github.enerccio.joi.protocol.node;

/**
 * {@code display} (since 1.4): the node shows her somewhere else now, as the user chose. The server starts her turn
 * about it ({@code turn} with the cause {@code looking_glass_entered} or {@code looking_glass_exited}), so she can react.
 * A node that shows her there as it starts (it remembered) does not send it.
 *
 * @param lookingGlass true when she is shown on a Looking Glass now, false when she is back in the node's window
 */
public record Display(boolean lookingGlass) {
}
