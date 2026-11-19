package com.github.enerccio.joi.protocol.http;

/**
 * {@code GET /api/v1}: what the server is and how to call it. Needs no auth.
 *
 * @param protocol the version of the API ({@code major.minor})
 * @param server   the name of the server software
 * @param version  its version
 * @param auth     how to authenticate
 */
public record ServerInfo(String protocol, String server, String version, AuthInfo auth) {
}
