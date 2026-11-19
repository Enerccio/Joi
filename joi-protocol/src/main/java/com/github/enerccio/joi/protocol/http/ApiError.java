package com.github.enerccio.joi.protocol.http;

/**
 * The body of every failed call (status 4xx / 5xx), the {@code error} event of a stream, which ends it, and the
 * {@code error} frame of the node WebSocket.
 *
 * @param code    a stable word to act on: {@code unauthorized}, {@code forbidden}, {@code not_found}, {@code bad_request},
 *                {@code no_model}, {@code model_failed}, {@code busy}, {@code internal}; on the node WebSocket also
 *                {@code stopped}, {@code unsupported_version}, {@code bad_message}
 * @param message what went wrong, for people
 */
public record ApiError(String code, String message) {
}
