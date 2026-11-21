package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;

/**
 * A tool of the node that the user switches on and off ({@link Notifications}, {@link ClipboardOffers}): she gets it in the
 * manifest only while the user allows it, and a change of the choice sends a new manifest through the {@link NodeTools} it is in.
 */
public interface NodeTool {

    /** The name in the manifest; she sees it as {@code <node>__<name>}. */
    String name();

    /** The tool as she is offered it now, or null when the user has not allowed it (she does not know it exists). */
    ToolDefinition offered();

    /** A call of hers (on a thread of its own: it may wait for the user). */
    ToolResult run(ToolCall call);

    /** The host this tool is in: where its approvals go, and who to tell that the choice changed. */
    void bind(NodeTools host);
}
