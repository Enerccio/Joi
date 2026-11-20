package com.github.enerccio.joi.protocol.http;

/**
 * One message of a task's transcript (since 1.6).
 *
 * @param role      {@code system}, {@code user}, {@code assistant} or {@code tool}
 * @param text      what it says, null when an assistant message only calls functions
 * @param toolCalls the functions an assistant message calls (JSON as the model wrote it), null for none
 */
public record TaskStep(String role, String text, String toolCalls) {
}
