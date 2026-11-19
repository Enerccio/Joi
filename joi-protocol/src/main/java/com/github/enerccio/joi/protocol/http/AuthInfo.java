package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * The ways in which the server takes calls now: the administrator chooses tokens and OAuth, the configuration of the
 * server can turn on {@link AuthMethod#NONE}.
 *
 * @param methods what is offered, in the order a client should prefer them
 * @param oauth   the endpoints, null when OAuth is not offered
 */
public record AuthInfo(List<AuthMethod> methods, OAuthEndpoints oauth) {
}
