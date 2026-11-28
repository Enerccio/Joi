package com.github.enerccio.joi.protocol.http;

/**
 * One exchange of her history (since 1.3): what the user said, or the kind of the event she reacted to, and her reply.
 *
 * @param id     the message's stable id
 * @param sentAt when it was said (ISO 8601 date and time)
 * @param type   {@code user} (the user's words are {@code text}) or {@code event} (something happened: {@code event}) or {@code log} (since 1.6: a note of the server, its words in
 *               {@code text} and its kind in {@code event}; not an exchange and never given to the model)
 * @param text   the user's words, null for an event
 * @param event  the kind of the event ({@code node.connected}, {@code node.first_boot}...), null for the user's words
 * @param node   the node it came from, null when it came through the HTTP API or the server
 * @param reply  her reply, null when she has not answered (a failed or stopped turn)
 * @param images the ids of the images the user sent with the words (since 1.11), each given by {@code GET /api/v1/images/{id}}; null for none
 * @param toolImages the ids of the pictures her tools brought in the turn of her reply (since 1.16), in the order they came, each given
 *               by {@code GET /api/v1/images/{id}}; null for none
 * @param summarized true when a summary covers the message (since 1.16): it is no longer given to her model; null or false when it is
 */
public record ChatEntry(String id, String sentAt, String type, String text, String event, String node, String reply,
                        java.util.List<String> images, java.util.List<String> toolImages, Boolean summarized) {

    public ChatEntry(String id, String sentAt, String type, String text, String event, String node, String reply) {
        this(id, sentAt, type, text, event, node, reply, null, null, null);
    }

    public ChatEntry(String id, String sentAt, String type, String text, String event, String node, String reply,
                     java.util.List<String> images) {
        this(id, sentAt, type, text, event, node, reply, images, null, null);
    }

    /** Whether a summary covers it (a null of an older server is false). */
    public boolean isSummarized() {
        return Boolean.TRUE.equals(summarized);
    }
}
