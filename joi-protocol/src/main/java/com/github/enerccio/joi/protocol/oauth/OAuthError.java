package com.github.enerccio.joi.protocol.oauth;

import com.google.gson.annotations.SerializedName;

/**
 * An error of an OAuth endpoint (RFC 6749, 5.2; RFC 8628, 3.5): {@code authorization_pending} and {@code slow_down}
 * while the user has not approved yet, {@code access_denied}, {@code expired_token}, {@code invalid_grant},
 * {@code invalid_request}, {@code unsupported_grant_type}, and {@code unavailable} when OAuth is turned off.
 */
public record OAuthError(@SerializedName("error") String error,
                         @SerializedName("error_description") String errorDescription) {
}
