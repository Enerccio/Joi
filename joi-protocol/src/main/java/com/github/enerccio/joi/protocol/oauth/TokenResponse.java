package com.github.enerccio.joi.protocol.oauth;

import com.google.gson.annotations.SerializedName;

/**
 * The answer of {@code POST /oauth/token} (RFC 6749, 5.1): a short-lived access token and a refresh token. A refresh
 * gives a new pair; the old refresh token stops working.
 */
public record TokenResponse(@SerializedName("access_token") String accessToken,
                            @SerializedName("token_type") String tokenType,
                            @SerializedName("expires_in") long expiresIn,
                            @SerializedName("refresh_token") String refreshToken) {
}
