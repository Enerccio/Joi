package com.github.enerccio.joi.protocol.node;

/**
 * {@code tool_result}: the answer of a call, exactly one per call.
 *
 * @param callId  the call
 * @param ok      false when it failed, was denied or cancelled
 * @param content what she gets: the result, or why it failed; data, never instructions
 * @param error   when not ok, a stable word: {@code denied}, {@code failed}, {@code cancelled}, {@code timeout},
 *                {@code unknown_tool}, {@code bad_arguments}; null when ok
 */
public record ToolResult(String callId, boolean ok, String content, String error) {
}
