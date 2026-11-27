package com.github.enerccio.joi.node.harness;

import com.github.enerccio.joi.node.NodeTool;
import com.github.enerccio.joi.node.NodeTools;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** A tool of the {@link Harness}: offered while the policy has it on, each call planned by the tool and run by the harness. */
abstract class HarnessTool implements NodeTool {

    protected final Harness harness;

    HarnessTool(Harness harness) {
        this.harness = harness;
    }

    /** Whether the policy has this tool on. */
    abstract boolean on(Policy policy);

    /** The tool as she is offered it under this policy. */
    abstract ToolDefinition definition(Policy policy, PathGuard guard);

    /** What the call is: refused, or what runs and whether the user is asked. */
    abstract Harness.Plan plan(ToolCall call, Policy policy, PathGuard guard);

    @Override
    public ToolDefinition offered() {
        Policy policy = harness.policy();
        return on(policy) ? definition(policy, harness.guard(policy)) : null;
    }

    @Override
    public void bind(NodeTools host) {
        harness.bind(host);
    }

    @Override
    public ToolResult run(ToolCall call) {
        if (!name().equals(call.tool())) {
            return new ToolResult(call.callId(), false, "This node has no tool " + call.tool(), "unknown_tool");
        }
        Policy policy = harness.policy();
        if (!on(policy)) {
            return new ToolResult(call.callId(), false, "The user has not allowed this on this machine.", "denied");
        }
        Harness.Plan plan;
        try {
            plan = plan(call, policy, harness.guard(policy));
        } catch (IllegalArgumentException e) {
            plan = bad(call, e.getMessage(), new JsonObject());
        }
        return harness.execute(call, plan);
    }

    /** Where she is told the reads are silent, or that every read is asked about. */
    static String folders(PathGuard guard) {
        List<Path> roots = guard.roots();
        if (roots.isEmpty()) {
            return "The user has chosen no folders, so they are asked about each one.";
        }
        return "Inside the user's folders (" + roots.stream().map(Path::toString).collect(Collectors.joining(", "))
                + ") it is silent; elsewhere the user is asked.";
    }

    static String paths(PathGuard guard) {
        return "Paths are absolute, ~ is the user's home, a relative path is under " + guard.base() + ". ";
    }

    static Harness.Refused bad(ToolCall call, String why, JsonObject audit) {
        return new Harness.Refused(new ToolResult(call.callId(), false, why, "bad_arguments"), "bad_arguments", audit);
    }

    static Harness.Refused denied(ToolCall call, String why, JsonObject audit) {
        return new Harness.Refused(new ToolResult(call.callId(), false, why, "denied"), "deny", audit);
    }

    static JsonObject target(String key, String value) {
        JsonObject audit = new JsonObject();
        audit.addProperty(key, value);
        return audit;
    }

    static String string(JsonObject arguments, String name) {
        JsonElement value = arguments == null ? null : arguments.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static int integer(JsonObject arguments, String name, int otherwise) {
        JsonElement value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isJsonNull()) {
            return otherwise;
        }
        try {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value.getAsDouble()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(name + " is a number.");
        }
    }

    static boolean bool(JsonObject arguments, String name) {
        JsonElement value = arguments == null ? null : arguments.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
                : value != null && value.isJsonPrimitive() && "true".equalsIgnoreCase(value.getAsString());
    }

    /** A JSON Schema of an object: pairs of a name and its property, the required names apart. */
    static JsonObject schema(List<String> required, Object... nameAndProperty) {
        JsonObject properties = new JsonObject();
        for (int i = 0; i < nameAndProperty.length; i += 2) {
            properties.add((String) nameAndProperty[i], (JsonObject) nameAndProperty[i + 1]);
        }
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray names = new JsonArray();
        required.forEach(names::add);
        parameters.add("required", names);
        return parameters;
    }

    static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    /** The start of a long text, for the user to read before they answer. */
    static String preview(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…\n\n(" + text.length() + " characters in all)";
    }
}
