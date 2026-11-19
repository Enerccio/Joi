package com.github.enerccio.joi.protocol.http;

/**
 * A personality of an assistant.
 *
 * @param key  its stable key
 * @param name its name in the user's language
 */
public record Personality(String key, String name) {
}
