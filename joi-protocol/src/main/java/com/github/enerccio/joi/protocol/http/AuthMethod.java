package com.github.enerccio.joi.protocol.http;

import com.google.gson.annotations.SerializedName;

/** How a client may prove who it is, as the server offers it ({@link AuthInfo#methods()}). */
public enum AuthMethod {

    /** No credentials: the server acts as one configured user (development and localhost setups). */
    @SerializedName("none")
    NONE,

    /** A token the user created in the server's UI, sent as {@code Authorization: Bearer joi_...}. */
    @SerializedName("token")
    TOKEN,

    /** OAuth 2.0: the device authorization grant gives an access token (sent the same way) and a refresh token. */
    @SerializedName("oauth")
    OAUTH
}
