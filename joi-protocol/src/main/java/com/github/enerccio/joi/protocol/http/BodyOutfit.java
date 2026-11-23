package com.github.enerccio.joi.protocol.http;

/**
 * An outfit of the body (since 1.12): a preset of its toggles that a node lists and puts on ({@code outfit} frame).
 *
 * @param id          the outfit's id
 * @param name        its name
 * @param description how she looks in it, null when it is not described
 * @param worn        whether she wears it now
 */
public record BodyOutfit(String id, String name, String description, boolean worn) {
}
