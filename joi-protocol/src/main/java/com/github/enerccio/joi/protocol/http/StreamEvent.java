package com.github.enerccio.joi.protocol.http;

/**
 * The events of a streamed chat ({@code text/event-stream}): {@code event: <name>} and {@code data: <JSON>}. The order:
 * {@code reasoning} and {@code text} pieces while the model writes, {@code reply} once the text is whole (and stored),
 * then {@code audio} pieces while she speaks, then {@code done}. {@code error} ends a stream that failed. Lines starting
 * with {@code :} are keep-alives.
 */
public enum StreamEvent {
    /** {@link TextDelta} of the thinking (only when asked for). */
    REASONING("reasoning", TextDelta.class),
    /** {@link TextDelta} of the reply. */
    TEXT("text", TextDelta.class),
    /** {@link ChatReply} without audio. */
    REPLY("reply", ChatReply.class),
    /** {@link AudioChunk}. */
    AUDIO("audio", AudioChunk.class),
    /** {@link Done}. */
    DONE("done", Done.class),
    /** {@link ApiError}. */
    ERROR("error", ApiError.class);

    private final String eventName;
    private final Class<? extends Record> dataClass;

    StreamEvent(String eventName, Class<? extends Record> dataClass) {
        this.eventName = eventName;
        this.dataClass = dataClass;
    }

    /** The name in {@code event:}. */
    public String eventName() {
        return eventName;
    }

    /** What {@code data:} holds. */
    public Class<? extends Record> dataClass() {
        return dataClass;
    }

    public static StreamEvent of(String eventName) {
        for (StreamEvent event : values()) {
            if (event.eventName.equals(eventName)) {
                return event;
            }
        }
        return null;
    }
}
