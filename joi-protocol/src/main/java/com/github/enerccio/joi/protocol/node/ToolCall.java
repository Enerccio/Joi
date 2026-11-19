package com.github.enerccio.joi.protocol.node;

import com.google.gson.JsonObject;

/**
 * {@code tool_call}: run a tool of the node. The node checks the arguments and its harness again and answers with
 * {@link ToolResult}; in between it may send {@link ToolProgress} and {@link Approval}.
 *
 * @param callId         the id of the call
 * @param tool           its name in the manifest, without the node's name
 * @param arguments      the arguments
 * @param turn           the id of the {@code say} whose turn called it, null when that turn is not this node's
 * @param timeoutSeconds how long the server waits after the last frame about the call; an approval that is asked
 *                       waits for the user
 */
public record ToolCall(String callId, String tool, JsonObject arguments, String turn, int timeoutSeconds) {
}
