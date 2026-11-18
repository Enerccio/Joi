package com.github.enerccio.joi.domain.collections;

/**
 * How much a {@code Memory} matters: it decides what is recalled first when the prompt has little room, and what a
 * cleanup of old memories may drop. Stored by name, so the names never change.
 */
public enum MemoryImportance {

    /** Always worth recalling when it matches: a promise, an allergy, a name, an exact instruction. */
    IMPORTANT,
    /** Worth recalling when it matches and there is room. */
    MEDIUM,
    /** Small talk worth keeping for a while; recalled last, the first to go. */
    TRIVIAL
}
