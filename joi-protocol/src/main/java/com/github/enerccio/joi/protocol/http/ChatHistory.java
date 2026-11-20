package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * {@code GET /api/v1/jois/{id}/chat} (since 1.3): her latest exchanges, oldest first, and whether there are earlier
 * ones (asked for with {@code before}, the id of the first one here).
 */
public record ChatHistory(List<ChatEntry> messages, boolean more) {
}
