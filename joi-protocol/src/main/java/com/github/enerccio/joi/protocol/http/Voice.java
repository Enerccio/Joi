package com.github.enerccio.joi.protocol.http;

/**
 * How an assistant speaks.
 *
 * @param model the name of the speech model
 * @param actor       the name of the voice actor
 * @param description how the voice sounds, in words, null when not described
 */
public record Voice(String model, String actor, String description) {
}
