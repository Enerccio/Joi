package com.github.enerccio.joi.domain.chat.turn;

/**
 * The steps of a chat turn, in order. {@link #INFERENCE} and {@link #CALL_TOOLS} loop: a round with calls goes to
 * the calls, the calls go back to the model, a round without calls goes on to {@link #STORE}.
 */
public enum TurnStepType {

    /** Her mood, the data of her system prompt. */
    PREPARE,
    /** The system prompt and the messages for the model. */
    PAYLOAD,
    /** The functions she may call. */
    TOOLS,
    /** One round of the model. */
    INFERENCE,
    /** The functions the model asked for, one after another. */
    CALL_TOOLS,
    /** The turn stored as a {@code ChatMessage} with its prompt. */
    STORE,
    /** The reply spoken. */
    SPEECH,
    /** The end of every turn: the caller learns how it went. */
    CLEANUP;

    /** The step that follows, null after the last one. */
    public TurnStepType next() {
        TurnStepType[] all = values();
        return ordinal() < all.length - 1 ? all[ordinal() + 1] : null;
    }
}
