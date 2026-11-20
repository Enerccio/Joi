package com.github.enerccio.joi.protocol.http;

/**
 * The body of {@code POST /api/v1/jois/{id}/tasks/{task}/deny} (since 1.6), may be left out.
 *
 * @param reason why the user does not allow it, told to her; null for none
 */
public record TaskDenial(String reason) {
}
