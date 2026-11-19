package com.github.enerccio.joi.protocol.node;

import com.github.enerccio.joi.protocol.http.JoiSummary;

/**
 * {@code welcome}, the answer to {@link Hello}: the node is connected and sends its {@link Manifest} next.
 *
 * @param protocol         the version the server speaks on this connection
 * @param server           the program of the server
 * @param version          its version
 * @param session          the id of this connection
 * @param joi              the assistant
 * @param heartbeatSeconds send a frame (a ping when nothing else) at least this often; a connection silent for twice
 *                         this is closed
 */
public record Welcome(String protocol, String server, String version, String session, JoiSummary joi,
                      int heartbeatSeconds) {
}
