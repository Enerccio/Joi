package com.github.enerccio.joi.protocol.oauth;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** {@code GET /.well-known/oauth-authorization-server} (RFC 8414): where the endpoints are. */
public record OAuthMetadata(@SerializedName("issuer") String issuer,
                            @SerializedName("device_authorization_endpoint") String deviceAuthorizationEndpoint,
                            @SerializedName("token_endpoint") String tokenEndpoint,
                            @SerializedName("grant_types_supported") List<String> grantTypesSupported,
                            @SerializedName("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethodsSupported) {
}
