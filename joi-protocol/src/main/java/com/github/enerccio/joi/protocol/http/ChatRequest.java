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
 * @param images    the ids of the images to look at with the words (since 1.11, from {@link ImageUpload}), at most {@link #MAX_IMAGES}; the
 *                  text may then be empty
 */
public record ChatRequest(String text, Boolean speak, Boolean stream, Boolean reasoning, String node, java.util.List<String> images) {

    /** How many images go with one message. */
    public static final int MAX_IMAGES = 4;

    public ChatRequest(String text, Boolean speak, Boolean stream, Boolean reasoning, String node) {
        this(text, speak, stream, reasoning, node, null);
    }

    public java.util.List<String> imagesOrEmpty() {
        return images == null ? java.util.List.of() : images;
    }

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
