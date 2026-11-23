package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.node.NodeTransport;

import java.util.List;

/**
 * The nodes connected over the node WebSocket ({@code DESIGN.md} → The node protocol, {@code asyncapi.yaml}): their
 * handshake, the tools each offers (to her turns, as {@code <node>__<tool>}), the turns they start ({@code say}) and
 * the server starts on them (her first waking up, the greeting when the user is back), the calls of their tools, the
 * events and the heartbeat. The transport is the endpoint's ({@code api/NodeEndpoint}).
 */
public interface NodeService {

    /** How often a node sends a frame at least; silent for twice this, it is closed. */
    int HEARTBEAT_SECONDS = 30;

    /** How long a new connection has to say hello. */
    int HELLO_SECONDS = 10;

    /** How long a tool call waits after the last frame about it (an asked approval waits for the user). */
    int TOOL_TIMEOUT_SECONDS = 120;

    /**
     * A connection was opened.
     *
     * @param authorization the {@code Authorization} header of the upgrade, null for none (then {@code hello.token})
     */
    NodeSession open(NodeTransport transport, String authorization);

    /** A text frame came from the node. */
    void receive(NodeSession session, String text);

    /** The connection is gone: its tools go, its calls fail and its turn stops. */
    void closed(NodeSession session);

    /** The nodes connected for the assistant (welcomed). */
    List<NodeSession> sessions(Joi joi);

    /**
     * The nodes connected for the assistant that offer the tool now, the one she is talking through first (it has a turn
     * running), the others in the order they connected.
     */
    List<NodeSession> sessionsWithTool(Joi joi, String tool);

    /**
     * Calls a tool of a node for a function of the server's own (a call of hers goes through {@code <node>__<tool>}): the result,
     * or the node's refusal, goes to the continuation. The user's harness on the node applies as to any call.
     */
    void callTool(NodeSession session, String tool, com.google.gson.JsonObject arguments,
                  com.github.enerccio.marginalia.domain.service.function.FunctionContinuation<Object> continuation);

    /** Tells her nodes that something changed on the server ({@code body}, {@code joi}): an {@code event} frame. */
    void notify(Joi joi, String event);

    /**
     * As {@link #notify(Joi, String)} for what changed in one profile: only the nodes in it are told. Null for all of them.
     */
    void notify(Joi joi, String event, String profile);

    /** The profile the node is in now (see {@code ProfileService}). */
    String profileOf(NodeSession session);

    /**
     * A task of hers was made or changed its status: a {@code task} frame to her nodes. One that waits is the node's to ask
     * the user about (the answer comes through the HTTP API).
     */
    void task(Joi joi, com.github.enerccio.joi.protocol.http.TaskInfo task);

    /**
     * Tells her something that happened, in a turn of hers on one of her connected nodes: what happens in a burst is told
     * together, and after the turn that runs now.
     *
     * @param cause why, as the node sees it ({@code task_ended}); null for the default
     * @return false when no node of hers is connected: the caller keeps the event for later
     */
    boolean tell(Joi joi, String text, String cause);

    /**
     * She plays an animation: an {@code animation} frame to her nodes.
     *
     * @return how many nodes were told (none when no node is connected)
     */
    int animate(Joi joi, String animation, String name, boolean loop);

    /**
     * Tells her nodes her mood and how it shows on her face (a {@code mood} frame): after the mood changed, or the user edited it.
     * Called by the user's own thread (the mood is looked up for the user in scope). A node that connects is told by itself.
     */
    void mood(Joi joi);
}
