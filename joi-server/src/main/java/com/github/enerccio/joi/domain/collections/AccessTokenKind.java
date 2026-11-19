package com.github.enerccio.joi.domain.collections;

/** How an {@link com.github.enerccio.joi.domain.model.impl.AccessToken} was made. Never rename: stored by name. */
public enum AccessTokenKind {

    /** Created by the user in User Settings → Access; works until revoked or until it expires. */
    TOKEN,

    /** Given to a device the user approved (OAuth device grant): a short-lived access token and a refresh token. */
    OAUTH
}
