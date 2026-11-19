package com.github.enerccio.joi.protocol.node;

import java.util.List;

/**
 * {@code hello}, the first frame of a node: who it is and which assistant the connection is for (one assistant per
 * connection; a node for two opens two).
 *
 * @param protocol the version the node speaks, {@code major.minor}
 * @param nodeId   its stable id, chosen once and kept (a UUID); a second connection of the same node and assistant
 *                 replaces the first
 * @param name     its short name ({@code desk}): its tools are offered to her as {@code desk.<tool>}
 * @param kind     what it is ({@code desktop}, {@code phone}, {@code pi}...), null when not said
 * @param software the program and its version, null when not said
 * @param joi      the id of the assistant
 * @param parts    what it implements
 * @param token    the credentials, for a client that cannot send {@code Authorization} on the upgrade; null otherwise
 */
public record Hello(String protocol, String nodeId, String name, String kind, String software, String joi,
                    List<NodePart> parts, String token) {
}
