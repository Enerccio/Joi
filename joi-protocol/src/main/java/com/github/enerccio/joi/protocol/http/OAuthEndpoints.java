package com.github.enerccio.joi.protocol.http;

/**
 * Where the OAuth calls are, as absolute URLs of the server.
 *
 * @param deviceAuthorization where a device asks for its codes (RFC 8628)
 * @param token               where it polls for its tokens and refreshes them
 * @param verification        the page the user approves the device on
 */
public record OAuthEndpoints(String deviceAuthorization, String token, String verification) {
}
