package com.github.enerccio.joi.protocol.oauth;

import com.google.gson.annotations.SerializedName;

/**
 * The answer of {@code POST /oauth/device_authorization} (RFC 8628, 3.2). The device shows the user code and the page
 * (or the complete URL, as a QR code), then polls {@code /oauth/token} every {@code interval} seconds.
 */
public record DeviceAuthorization(@SerializedName("device_code") String deviceCode,
                                  @SerializedName("user_code") String userCode,
                                  @SerializedName("verification_uri") String verificationUri,
                                  @SerializedName("verification_uri_complete") String verificationUriComplete,
                                  @SerializedName("expires_in") long expiresIn,
                                  @SerializedName("interval") long interval) {
}
