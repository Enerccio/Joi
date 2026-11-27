package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * {@code GET /api/v1/me}: whom the call acts for.
 *
 * @param login  the login of the user
 * @param name   their full name, else the login
 * @param method how the call was authenticated
 * @param scopes the parts of the administration the token may use now (since 1.13): empty for a token without them, an
 *               OAuth device, the local node and no auth; null from a server before
 */
public record Me(String login, String name, AuthMethod method, List<AdminScope> scopes) {
}
