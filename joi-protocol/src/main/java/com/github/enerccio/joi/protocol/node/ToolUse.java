package com.github.enerccio.joi.protocol.node;

/**
 * {@code tool}: she used a tool in the turn (of any node, or of the server), after it answered.
 *
 * @param name      the tool as she called it ({@code desk__read_file})
 * @param arguments the arguments, JSON
 * @param result    what it answered, null for nothing
 * @param ok        false when it failed or was denied
 */
public record ToolUse(String name, String arguments, String result, boolean ok) {
}
