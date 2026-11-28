package com.github.enerccio.joi.domain.chat;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.List;

/**
 * One tool she used in a turn, with what she gave it and what it answered. A turn's usages are stored on its
 * {@code ChatMessage} (JSON) and listed in the Chats tab, the ones of the server and of the nodes alike.
 *
 * @param name      the function's name ({@code <node>__<tool>} for a node's)
 * @param arguments the arguments she wrote (JSON text), may be blank
 * @param result    what it answered (or why it failed)
 * @param failed    whether it failed
 * @param kept      whether the call and its result stay in her history (her later prompts replay them): the tool said so when it was
 *                  offered, and the call worked. Decided once, at the call, so a later change never rewrites the history the cache holds
 */
public record ToolUsage(String name, String arguments, String result, boolean failed, boolean kept) {

    /** A usage that is not kept in her history. */
    public ToolUsage(String name, String arguments, String result, boolean failed) {
        this(name, arguments, result, failed, false);
    }

    private static final Gson GSON = new Gson();

    /** The usages as the message stores them, null for none. */
    public static String toJson(List<ToolUsage> usages) {
        return usages == null || usages.isEmpty() ? null : GSON.toJson(usages);
    }

    /** What a message stored: the usages in the order they happened, none for null or what cannot be read. */
    public static List<ToolUsage> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ToolUsage> usages = GSON.fromJson(json, new TypeToken<List<ToolUsage>>() {
            }.getType());
            return usages == null ? List.of() : usages;
        } catch (JsonParseException e) {
            return List.of();
        }
    }
}
