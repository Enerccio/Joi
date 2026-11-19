package com.github.enerccio.joi.protocol.node;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * {@code manifest}: what the node can do, after {@link Welcome} and again whenever it changes (a permission granted,
 * a program installed, the policy edited, a local MCP server added).
 *
 * @param version      grows with each change; the server keeps the highest and ignores older ones
 * @param os           the operating system, null when not said
 * @param arch         the architecture, null when not said
 * @param capabilities what the node has, free form (microphone, screen capture, the holographic display, the policy
 *                     summary...), null for nothing
 * @param tools        every tool it offers now
 */
public record Manifest(long version, String os, String arch, JsonObject capabilities, List<ToolDefinition> tools) {
}
