package com.github.enerccio.joi.protocol.node;

/**
 * {@code approval}: the node asked the user about a call of the ask tier ({@link ApprovalState#ASKED}), then what
 * they answered. The server shows it and waits; the result still comes as {@link ToolResult}.
 *
 * @param callId the call
 * @param state  where it is
 * @param scope  when allowed, {@code once} or {@code session}; null otherwise
 * @param via    how it was answered ({@code click}, {@code voice}, {@code deck}...), null when not said
 */
public record Approval(String callId, ApprovalState state, String scope, String via) {
}
