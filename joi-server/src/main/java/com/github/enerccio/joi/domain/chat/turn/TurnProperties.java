package com.github.enerccio.joi.domain.chat.turn;

/**
 * The keys of the values a turn keeps in its properties ({@code getProperties}), and when each is there. A listener
 * may keep its own values too, under a key prefixed with its package.
 */
public final class TurnProperties {

    /** The id of the turn (a UUID), from the start. */
    public static final String RUN_ID = "joi.runId";
    /** A piece of text ({@code String}), at the chunk events. */
    public static final String CHUNK = "joi.chunk";
    /** The text of the round ({@code String}), at {@link TurnEvents#AFTER_INFERENCE}. */
    public static final String ROUND_TEXT = "joi.roundText";
    /** The calls of the round ({@code List<LLMToolCall>}), at {@link TurnEvents#AFTER_INFERENCE}. */
    public static final String ROUND_CALLS = "joi.roundCalls";
    /** How many rounds the model answered ({@code Integer}), from the first {@link TurnEvents#AFTER_INFERENCE}. */
    public static final String ROUNDS = "joi.rounds";
    /** The call ({@code LLMToolCall}), at {@link TurnEvents#BEFORE_TOOL_CALL} and {@link TurnEvents#AFTER_TOOL_CALL}. */
    public static final String TOOL_CALL = "joi.toolCall";
    /** What the model is told of the call ({@code String}), see {@link TurnEvents#BEFORE_TOOL_CALL}. */
    public static final String TOOL_RESULT = "joi.toolResult";
    /** Whether the call failed ({@code Boolean}), with {@link #TOOL_RESULT}. */
    public static final String TOOL_FAILED = "joi.toolFailed";
    /** What she says ({@code String}), at {@link TurnEvents#BEFORE_SPEECH}. */
    public static final String SPEECH_TEXT = "joi.speechText";
    /** Why some or all of the speech is missing ({@code String}), at {@link TurnEvents#AFTER_SPEECH}, null when nothing is. */
    public static final String SPEECH_ERROR = "joi.speechError";
    /** {@code Boolean.TRUE} when a round failed after others had answered and what the turn had done was stored anyway, see {@code ChatServiceImpl}. */
    public static final String PARTIAL = "joi.partial";
    /** What a model that can see wrote about each image of the user's message ({@code List<String>}), set by the payload when the chat model cannot see. */
    public static final String IMAGE_NOTES = "joi.imageNotes";
    /** The images she asked to look at with {@code view_image} in this round ({@code ShownImages}), there when her chat model sees images. */
    public static final String SHOWN_IMAGES = "joi.shownImages";
    /** The pictures of her tools' results shown to her in this turn ({@code List<String>} of image uuids), kept with her message. */
    public static final String TOOL_IMAGES = "joi.toolImages";
    /** How the turn went ({@link TurnOutcome}), at {@link TurnEvents#AFTER_CLEANUP}. */
    public static final String OUTCOME = "joi.outcome";

    private TurnProperties() {
    }
}
