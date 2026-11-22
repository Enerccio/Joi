package com.github.enerccio.joi.domain.collections;

/** How an {@link com.github.enerccio.joi.domain.model.impl.AccessToken} was made. Never rename: stored by name. */
public enum AccessTokenKind {

    /** Created by the user in User Settings → Access; works until revoked or until it expires. */
    TOKEN,

    /** Given to a device the user approved (OAuth device grant): a short-lived access token and a refresh token. */
    OAUTH,

    /**
     * Given to the node on the server's own machine over the local socket ({@code POST /oauth/local}): an access token with no refresh
     * token, made again over the socket when it expires. It works whatever the administrator offers to other clients.
     */
    LOCAL
}
