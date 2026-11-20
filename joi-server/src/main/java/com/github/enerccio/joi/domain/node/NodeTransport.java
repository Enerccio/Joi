package com.github.enerccio.joi.domain.node;

/**
 * The connection of a node as a {@link NodeSession} sees it: text frames out, and closing it. The WebSocket endpoint
 * ({@code api/NodeEndpoint}) implements it; tests use their own. Sends may come from several threads (the turn's,
 * the heartbeat's): an implementation sends one frame at a time.
 */
public interface NodeTransport {

    /** Sends a text frame; a connection that is gone drops it. */
    void send(String text);

    /** Closes the connection with a WebSocket close code and a reason. */
    void close(int code, String reason);
}
