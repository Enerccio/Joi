package com.github.enerccio.joi.protocol.node;

/**
 * {@code event}: something changed on the server that the node may show. A name the node does not know is ignored.
 *
 * @param name {@code body} (the body she is in, its parts or its animations: read {@code GET /jois/{id}/body} again),
 *             {@code joi} (her name, mood or voice: read {@code GET /jois/{id}} again)
 * @param at   when (ISO 8601)
 */
public record ServerEvent(String name, String at) {
}
