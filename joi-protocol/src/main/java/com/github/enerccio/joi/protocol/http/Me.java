package com.github.enerccio.joi.protocol.http;

/**
 * {@code GET /api/v1/me}: whom the call acts for.
 *
 * @param login  the login of the user
 * @param name   their full name, else the login
 * @param method how the call was authenticated
 */
public record Me(String login, String name, AuthMethod method) {
}
