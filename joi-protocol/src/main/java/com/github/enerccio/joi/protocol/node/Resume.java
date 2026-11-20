package com.github.enerccio.joi.protocol.node;

/**
 * What a node that connects again says of the connection it lost ({@link Hello#resume()}, since 1.5): it lets the server
 * tell a server that was down from a node that only lost the network.
 *
 * @param serverStartedAt the {@code welcome.serverStartedAt} of the connection that was lost; a server that started
 *                        since then was down, whatever the reason (shut down, crashed, restarted)
 * @param lostAt          when the node lost the connection (ISO 8601): how long the server was gone is from then
 * @param closeCode       the close code the node got ({@code 1001}: the server said it was going down; {@code 1006}: the
 *                        connection simply broke); null when it did not get one
 */
public record Resume(String serverStartedAt, String lostAt, Integer closeCode) {
}
