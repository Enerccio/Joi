package com.github.enerccio.joi.protocol.http;

/**
 * {@code POST /api/v1/jois/{id}/chat}: what the user said.
 *
 * @param text      the words of the user
 * @param speak     whether to speak the reply too (default true; ignored when she has no voice)
 * @param stream    whether to answer as server-sent events ({@link StreamEvent}) instead of one {@link ChatReply};
 *                  {@code Accept: text/event-stream} does the same
 * @param reasoning whether to include the thinking of the model (default false)
 * @param node      the name of the node the words came from, null for none
 */
public record ChatRequest(String text, Boolean speak, Boolean stream, Boolean reasoning, String node) {

    public boolean speakOrDefault() {
        return speak == null || speak;
    }

    public boolean streamOrDefault() {
        return stream != null && stream;
    }

    public boolean reasoningOrDefault() {
        return reasoning != null && reasoning;
    }
}
