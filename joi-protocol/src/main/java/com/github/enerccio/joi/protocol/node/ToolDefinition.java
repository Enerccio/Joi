package com.github.enerccio.joi.protocol.node;

import com.google.gson.JsonObject;

/**
 * A tool of a node.
 *
 * @param name        unique in the node ({@code read_file}); she sees it as {@code <node>__<name>} (model APIs allow no dots in names)
 * @param description what it does, for the model
 * @param parameters  a JSON Schema of the arguments (an object)
 * @param tier        its harness tier
 * @param keepResult  whether her calls of it and what it answers stay in her history (since 1.18), null for no
 */
public record ToolDefinition(String name, String description, JsonObject parameters, ToolTier tier, Boolean keepResult) {

    /** A tool whose results are not kept in her history. */
    public ToolDefinition(String name, String description, JsonObject parameters, ToolTier tier) {
        this(name, description, parameters, tier, null);
    }

    /** Whether what it answers stays in her history. */
    public boolean isKeepResult() {
        return Boolean.TRUE.equals(keepResult);
    }
}
