package com.github.enerccio.joi.protocol.http;

/**
 * An assistant in the list.
 *
 * @param id          her stable id (a UUID)
 * @param name        her name
 * @param lastChatted when she was last talked to (ISO 8601), null for never
 */
public record JoiSummary(String id, String name, String lastChatted) {
}
