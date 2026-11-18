package com.github.enerccio.joi.domain.collections;

/**
 * Who wrote a {@code JoiTaskMessage}, as in a chat completion. Stored by name, so the names never change.
 */
public enum TaskMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** The result of a tool call of the assistant. */
    TOOL
}
