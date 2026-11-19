package com.github.enerccio.joi.domain.chat.turn;

/**
 * The events of a chat turn, in the order they come. A listener ({@link TurnEvent}) may change what the event names
 * and has to call {@code chain.next()}; an observer ({@link TurnObserver}) only looks. The keys of the values an event
 * carries are in {@link TurnProperties}.
 */
public enum TurnEvents {

    /** The turn starts: the assistant, her chat model and the input are known. */
    BEFORE_PREPARE,
    /** Today's mood is known and the data of her system prompt is filled ({@code getTemplateData}): change it here. */
    AFTER_TEMPLATE_DATA,
    /** The messages for the model are built ({@code getPayload}): change them here. */
    AFTER_PREPARE_PAYLOAD,
    /**
     * The functions she may call are collected: a listener adds its own with
     * {@code setFunctions(getFunctions().and(mine))}. Empty by default.
     */
    COLLECT_TOOLS,
    /** A round is asked of the model (the first one, and one after each round of calls). */
    BEFORE_INFERENCE,
    /** A piece of the thinking of the model ({@link TurnProperties#CHUNK}); for observers only, the stream does not wait. */
    REASONING_CHUNK_RECEIVED,
    /** A piece of the reply ({@link TurnProperties#CHUNK}); for observers only, the stream does not wait. */
    CHUNK_RECEIVED,
    /**
     * The model answered a round: its text ({@link TurnProperties#ROUND_TEXT}) and the calls it asks for
     * ({@link TurnProperties#ROUND_CALLS}, empty for none). The reply so far is {@code getReply}.
     */
    AFTER_INFERENCE,
    /**
     * A function is about to be called ({@link TurnProperties#TOOL_CALL}). A listener that puts
     * {@link TurnProperties#TOOL_RESULT} answers instead of the function (a refusal, the user's answer), and
     * {@link TurnProperties#TOOL_FAILED} marks that answer as an error.
     */
    BEFORE_TOOL_CALL,
    /** A function answered ({@link TurnProperties#TOOL_RESULT}, {@link TurnProperties#TOOL_FAILED}): the result can be changed. */
    AFTER_TOOL_CALL,
    /** The reply is whole ({@code getReply}) and about to be stored: change it here. */
    BEFORE_STORE,
    /** The turn is stored ({@code getMessage}). */
    AFTER_STORE,
    /**
     * The reply is about to be spoken: {@link TurnProperties#SPEECH_TEXT} is what she says, blank for silence. Only
     * when the turn is spoken and she has a voice.
     */
    BEFORE_SPEECH,
    /** The speech is done ({@link TurnProperties#SPEECH_ERROR} when some or all of it is missing). */
    AFTER_SPEECH,
    /**
     * The last event of every turn, completed, stopped or failed. Unlike the others its listeners run also when the
     * turn was stopped; {@link TurnProperties#OUTCOME} says how it went.
     */
    AFTER_CLEANUP;

    /** Whether the listeners of the event run although the turn was stopped. */
    public boolean isTerminal() {
        return this == AFTER_CLEANUP;
    }

    /** Whether only observers see the event (it comes too often to wait for listeners). */
    public boolean isObservedOnly() {
        return this == CHUNK_RECEIVED || this == REASONING_CHUNK_RECEIVED;
    }
}
