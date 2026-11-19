package com.github.enerccio.joi.protocol.http;

/**
 * An animation a body plays (its steps and clips are in the {@code .joia}).
 *
 * @param id          the id of the animation, as in the {@code .joia}
 * @param name        its name
 * @param description what it shows, null when not described
 * @param loop        whether the whole animation starts again after its last step until it is finished
 */
public record BodyAnimation(String id, String name, String description, boolean loop) {
}
