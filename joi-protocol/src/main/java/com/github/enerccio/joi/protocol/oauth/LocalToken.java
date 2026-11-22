package com.github.enerccio.joi.protocol.oauth;

import com.google.gson.annotations.SerializedName;

/**
 * The answer of {@code POST /oauth/local} (since 1.8), given over the server's local socket only: the access token of a node on the
 * server's own machine. There is no refresh token; the node asks again over the socket when it expires.
 */
public record LocalToken(@SerializedName("access_token") String accessToken,
                         @SerializedName("token_type") String tokenType,
                         @SerializedName("expires_in") long expiresIn) {
}
