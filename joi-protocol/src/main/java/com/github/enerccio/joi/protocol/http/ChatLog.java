package com.github.enerccio.joi.protocol.http;

/**
 * A note of the server in her conversation (since 1.6): something that happened and should be seen in the transcript,
 * such as the user's approval of a task, standing or not. It is **not an exchange**: it is never given to the model, and
 * it does not count as her having been talked to. A client reads it in the history ({@link ChatEntry} of the type
 * {@code log}) and a node is sent it live as the {@code log} frame.
 *
 * @param id     the message's stable id
 * @param sentAt when it happened (ISO 8601 date and time)
 * @param kind   what kind of note ({@code task.approved}, {@code task.auto_approved}...); a client ignores kinds it does not know but shows the text
 * @param text   the note, for the user
 */
public record ChatLog(String id, String sentAt, String kind, String text) {
}
