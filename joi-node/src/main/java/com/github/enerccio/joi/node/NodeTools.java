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

        /** A call still runs (a long command): its timeout on the server starts again. */
        default void progress(String callId, String message) {
        }
    }

    /** The user's answer to a call of the harness: no, this once, or the same again until the node quits. */
    public enum Scope {
        DENIED, ONCE, SESSION
    }

    /** The user's answer to a call that may be allowed for the session; waits for it. */
    public interface ScopedAnswer {
        Scope get() throws InterruptedException;
    }

    /** The user's answer to a call that asks: true to go on; waits for it. */
    public interface Answer {
        boolean get() throws InterruptedException;
    }

    private final List<NodeTool> tools;
    private final AtomicLong version = new AtomicLong(1);
    private volatile Frames frames;

    public NodeTools(NodeTool... tools) {
        this(List.of(tools));
    }

    public NodeTools(List<NodeTool> tools) {
        this.tools = List.copyOf(tools);
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
    public void changed() {
        Frames to = frames;
        if (to != null) {
            to.manifest(manifest(version.incrementAndGet()));
        }
    }

    /** Tells the server that a call still runs; nothing when not connected. */
    public void progress(String callId, String message) {
        Frames to = frames;
        if (to != null) {
            to.progress(callId, message);
        }
    }

    /**
     * Asks the user about a call that may be allowed for the session: the server is told that it is asked, then the answer
     * ({@code once} or {@code session}, or denied).
     *
     * @param via how the user answers ({@code click}, {@code keyboard}...)
     */
    public static Scope askScoped(NodeTools host, String callId, String via, ScopedAnswer answer) throws InterruptedException {
        Frames to = host == null ? null : host.frames;
        if (to != null) {
            to.approval(callId, ApprovalState.ASKED, null, null);
        }
        Scope scope = Scope.DENIED;
        try {
            scope = answer.get();
            if (scope == null) {
                scope = Scope.DENIED;
            }
        } finally {
            if (to != null) {
                to.approval(callId, scope == Scope.DENIED ? ApprovalState.DENIED : ApprovalState.ALLOWED,
                        switch (scope) {
                            case DENIED -> null;
                            case ONCE -> "once";
                            case SESSION -> "session";
                        }, via);
            }
        }
        return scope;
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
