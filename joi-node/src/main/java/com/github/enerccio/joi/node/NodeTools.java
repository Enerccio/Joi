package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tools of the node the user decides on ({@link NodeTool}): the manifest with the ones that are on, the routing of a call to
 * its tool, and the frames to the server (the approvals of a call that asks the user, and a new manifest when a choice changes,
 * so she gets a tool, or loses it, at once). {@link NodeSocket} is the {@link Frames} once connected.
 */
public final class NodeTools {

    /** Where the node's frames go: the approval of a call, and a new manifest. */
    public interface Frames {
        void approval(String callId, ApprovalState state, String scope, String via);

        void manifest(Manifest manifest);
    }

    /** The user's answer to a call that asks: true to go on; waits for it. */
    public interface Answer {
        boolean get() throws InterruptedException;
    }

    private final List<NodeTool> tools;
    private final AtomicLong version = new AtomicLong(1);
    private volatile Frames frames;

    public NodeTools(NodeTool... tools) {
        this.tools = List.of(tools);
        this.tools.forEach(tool -> tool.bind(this));
    }

    /** Where a new manifest and the approvals go once connected. */
    public void attach(Frames frames) {
        this.frames = frames;
    }

    /** The manifest for the choices now: the tools that are on, none when all are off. */
    public Manifest manifest() {
        return manifest(version.get());
    }

    private Manifest manifest(long version) {
        List<ToolDefinition> offered = new ArrayList<>();
        for (NodeTool tool : tools) {
            ToolDefinition definition = tool.offered();
            if (definition != null) {
                offered.add(definition);
            }
        }
        return new Manifest(version, System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("os.arch"), null, offered);
    }

    /** A choice changed: she is sent the new manifest. */
    void changed() {
        Frames to = frames;
        if (to != null) {
            to.manifest(manifest(version.incrementAndGet()));
        }
    }

    /** A call of hers: to the tool of its name. */
    public ToolResult run(ToolCall call) {
        for (NodeTool tool : tools) {
            if (tool.name().equals(call.tool())) {
                return tool.run(call);
            }
        }
        return new ToolResult(call.callId(), false, "This node has no tool " + call.tool(), "unknown_tool");
    }

    /**
     * Asks the user about a call: the server is told that it is asked, and then that it was allowed or denied (the answer
     * of the user, or none in time, which is a no).
     *
     * @param host the node's tools, null when the tool runs on its own (nothing is told to a server)
     * @return whether the user allowed it
     */
    static boolean ask(NodeTools host, String callId, Answer answer) throws InterruptedException {
        Frames to = host == null ? null : host.frames;
        if (to != null) {
            to.approval(callId, ApprovalState.ASKED, null, null);
        }
        boolean allowed = answer.get();
        if (to != null) {
            to.approval(callId, allowed ? ApprovalState.ALLOWED : ApprovalState.DENIED, allowed ? "once" : null, "click");
        }
        return allowed;
    }
}
