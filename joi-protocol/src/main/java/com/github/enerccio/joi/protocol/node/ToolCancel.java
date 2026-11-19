package com.github.enerccio.joi.protocol.node;

/**
 * {@code tool_cancel}: the server no longer wants the result (the turn was stopped or timed out). The node still
 * answers with {@link ToolResult}, {@code cancelled} when it stopped in time.
 *
 * @param callId the call
 * @param reason why, for people, null for none
 */
public record ToolCancel(String callId, String reason) {
}
