package com.github.enerccio.joi.domain.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pictures that came with the results of her tools (a node's {@code tool_result.images}, protocol 1.14), until the turn shows them
 * to her: a function's result is text only, so {@code CallToolsStep} puts them into a message of their own after the results of the
 * round. Kept by assistant: her turns run one after another, so what waits is of the turn that runs.
 */
public class ToolImages {

    /** A picture and the function whose result brought it ({@code desk__view_image}). */
    public record Shown(String tool, String image) {
    }

    private final Map<Long, List<Shown>> waiting = new ConcurrentHashMap<>();

    /** The pictures of a result of {@code tool}, in order. */
    public void add(Long joiId, String tool, List<String> images) {
        if (joiId == null || images == null || images.isEmpty()) {
            return;
        }
        waiting.compute(joiId, (_, list) -> {
            List<Shown> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            images.forEach(image -> next.add(new Shown(tool, image)));
            return next;
        });
    }

    /** Takes what waits for the assistant, in the order it came; empty for none. */
    public List<Shown> take(Long joiId) {
        List<Shown> list = joiId == null ? null : waiting.remove(joiId);
        return list == null ? List.of() : list;
    }
}
