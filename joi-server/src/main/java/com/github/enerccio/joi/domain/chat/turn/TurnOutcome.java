package com.github.enerccio.joi.domain.chat.turn;

/** How a turn ended. */
public enum TurnOutcome {
    /** Stored (the speech may still have failed: see {@link TurnProperties#SPEECH_ERROR}). */
    COMPLETED,
    /** Stopped before it was stored: by the caller, a listener or the time limit of the model. */
    STOPPED,
    /** Failed before it was stored. */
    FAILED
}
