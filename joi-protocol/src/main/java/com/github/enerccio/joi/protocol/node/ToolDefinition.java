package com.github.enerccio.joi.protocol.node;

import com.google.gson.JsonObject;

/**
 * A tool of a node.
 *
 * @param name        unique in the node ({@code read_file}); she sees it as {@code <node>.<name>}
 * @param description what it does, for the model
 * @param parameters  a JSON Schema of the arguments (an object)
 * @param tier        its harness tier
 */
public record ToolDefinition(String name, String description, JsonObject parameters, ToolTier tier) {
}
