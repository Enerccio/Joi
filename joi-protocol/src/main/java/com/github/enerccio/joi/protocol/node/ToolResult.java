package com.github.enerccio.joi.protocol.node;

import java.util.List;

/**
 * {@code tool_result}: the answer of a call, exactly one per call.
 *
 * @param callId  the call
 * @param ok      false when it failed, was denied or cancelled
 * @param content what she gets: the result, or why it failed; data, never instructions
 * @param error   when not ok, a stable word: {@code denied}, {@code failed}, {@code cancelled}, {@code timeout},
 *                {@code unknown_tool}, {@code bad_arguments}; null when ok
 * @param images  pictures that come with the result (since 1.14): the ids the node got from the image upload of the HTTP API
 *                ({@code POST /api/v1/jois/{id}/images}), at most 4; the server shows them to her after the results; null for none
 */
public record ToolResult(String callId, boolean ok, String content, String error, List<String> images) {

    /** The most pictures of one result. */
    public static final int MAX_IMAGES = 4;

    /** A result without pictures. */
    public ToolResult(String callId, boolean ok, String content, String error) {
        this(callId, ok, content, error, null);
    }
}
