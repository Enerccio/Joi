package com.github.enerccio.joi.protocol.node;

/**
 * {@code tool_progress}: a call is still running (it also restarts the call's timeout).
 *
 * @param callId  the call
 * @param message what it does now, for people, null for nothing
 */
public record ToolProgress(String callId, String message) {
}
