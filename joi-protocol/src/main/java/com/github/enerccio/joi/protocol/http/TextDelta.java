package com.github.enerccio.joi.protocol.http;

/**
 * The {@code text} and {@code reasoning} events of a stream: the next piece of the reply (or of the thinking), as the
 * model writes it.
 */
public record TextDelta(String delta) {
}
