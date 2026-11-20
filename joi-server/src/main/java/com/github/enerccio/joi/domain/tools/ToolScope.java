package com.github.enerccio.joi.domain.tools;

/**
 * Whose settings of a tool a {@code ToolMetadata} holds: the three links of the chain, the most specific first. Stored
 * by name, so the names never change.
 */
public enum ToolScope {

    /** One assistant's. */
    JOI,
    /** The user's, for all their assistants. */
    USER,
    /** The administrator's, for everybody (the ceiling of the chain). */
    APP
}
