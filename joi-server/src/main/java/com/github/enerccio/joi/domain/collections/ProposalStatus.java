package com.github.enerccio.joi.domain.collections;

/** Where the LLM's proposal of a body's self-image is. Stored by name, so the names never change. */
public enum ProposalStatus {

    /** The model is looking at the snapshots. */
    RUNNING,
    /** The proposal is there to review. */
    DONE,
    /** The last request failed (the error says why). */
    FAILED
}
