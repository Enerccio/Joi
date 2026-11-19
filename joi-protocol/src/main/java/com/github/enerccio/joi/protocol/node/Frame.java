package com.github.enerccio.joi.protocol.node;

import com.google.gson.JsonElement;

/**
 * Every message of the node WebSocket ({@link com.github.enerccio.joi.protocol.JoiProtocol#NODE}): one JSON text frame.
 *
 * @param type the kind of message, {@link NodeMessage}; a type the receiver does not know is ignored
 * @param id   set by the sender when an answer refers to it, unique per connection and sender; the id of a
 *             {@code say} is the id of its turn
 * @param ref  the id of the frame this one answers or belongs to
 * @param data the message, of {@link NodeMessage#dataClass()}; null for {@code ping} and {@code pong}
 */
public record Frame(String type, String id, String ref, JsonElement data) {
}
