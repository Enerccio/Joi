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
    /**
     * What she is told of the summaries the turn made because her history would not fit her chat model's context ({@code List<String>},
     * from {@code SummarizerService.makeRoom}): given to her before the words of the turn and stored with them.
     */
    public static final String SUMMARY_EVENTS = "joi.summaryEvents";
    /**
     * The functions of the turn whose results stay in her history ({@code Set<String>}): each one that offers a function says so while the
     * tools are collected ({@link #keep}). Read when a call is answered, and stored with it ({@code ToolUsage.kept}).
     */
    public static final String KEPT_TOOLS = "joi.keptTools";
    /** Set ({@code Boolean.TRUE}) once the turn asked for room in her context: it asks once. */
    public static final String ROOM_ASKED = "joi.roomAsked";
    /** How the turn went ({@link TurnOutcome}), at {@link TurnEvents#AFTER_CLEANUP}. */
    public static final String OUTCOME = "joi.outcome";

    /** Marks the functions as ones whose results stay in her history, from a listener of {@code COLLECT_TOOLS}. */
    @SuppressWarnings("unchecked")
    public static void keep(java.util.Map<String, Object> properties, java.util.Collection<String> functions) {
        if (functions == null || functions.isEmpty()) {
            return;
        }
        ((java.util.Set<String>) properties.computeIfAbsent(KEPT_TOOLS, _ -> java.util.concurrent.ConcurrentHashMap.newKeySet())).addAll(functions);
    }

    /** Whether the result of the function stays in her history. */
    @SuppressWarnings("unchecked")
    public static boolean kept(java.util.Map<String, Object> properties, String function) {
        Object kept = properties.get(KEPT_TOOLS);
        return kept instanceof java.util.Set<?> set && set.contains(function);
    }

    private TurnProperties() {
    }
}
