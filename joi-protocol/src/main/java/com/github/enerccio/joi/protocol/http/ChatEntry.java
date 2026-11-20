package com.github.enerccio.joi.protocol.http;

/**
 * One exchange of her history (since 1.3): what the user said, or the kind of the event she reacted to, and her reply.
 *
 * @param id     the message's stable id
 * @param sentAt when it was said (ISO 8601 date and time)
 * @param type   {@code user} (the user's words are {@code text}) or {@code event} (something happened: {@code event})
 * @param text   the user's words, null for an event
 * @param event  the kind of the event ({@code node.connected}, {@code node.first_boot}...), null for the user's words
 * @param node   the node it came from, null when it came through the HTTP API or the server
 * @param reply  her reply, null when she has not answered (a failed or stopped turn)
 */
public record ChatEntry(String id, String sentAt, String type, String text, String event, String node, String reply) {
}
