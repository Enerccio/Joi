package com.github.enerccio.joi.protocol;

/**
 * The version and the paths of Joi's API. The JSON Schemas and the OpenAPI description in {@code joi-protocol/} (in this JAR)
 * are the source of truth; the records of {@link com.github.enerccio.joi.protocol.http},
 * {@link com.github.enerccio.joi.protocol.oauth} and {@link com.github.enerccio.joi.protocol.node} follow them (checked by a test).
 * <p>
 * Versioning: {@code major.minor}. A minor version only adds fields, calls and events; clients ignore what they do not
 * know. A new major version gets a new path ({@code /api/v2}).
 */
public final class JoiProtocol {

    /** The version this JAR describes: the HTTP API and the node WebSocket ({@link #NODE}, since 1.2). */
    public static final String VERSION = "1.18";

    /** The version of the node WebSocket. */
    public static final String NODE_VERSION = VERSION;

    /** The calls of the API; {@code GET} of it is the {@link com.github.enerccio.joi.protocol.http.ServerInfo} and needs no auth. */
    public static final String API = "/api/v1";

    /**
     * The node WebSocket: {@code GET} with {@code Upgrade: websocket}, authenticated as the API (or by
     * {@link com.github.enerccio.joi.protocol.node.Hello#token()}); its messages are
     * {@link com.github.enerccio.joi.protocol.node.Frame}s.
     */
    public static final String NODE = API + "/node";

    /** The OAuth 2.0 endpoints (device authorization grant, RFC 8628, and refresh tokens). */
    public static final String OAUTH = "/oauth";

    public static final String DEVICE_AUTHORIZATION = OAUTH + "/device_authorization";

    public static final String TOKEN = OAUTH + "/token";

    /**
     * The token of a node on the server's own machine (since 1.8): {@code POST} over the server's local socket (a Unix domain
     * socket in the data folder; the desktop app opens it) gives a {@link com.github.enerccio.joi.protocol.oauth.LocalToken} with no
     * key and no login, the socket being the proof that the caller is on the machine and may read the data folder. A call that did
     * not arrive on that socket is answered as an unknown path.
     */
    public static final String LOCAL = OAUTH + "/local";

    /** The OAuth metadata of the server (RFC 8414). */
    public static final String OAUTH_METADATA = "/.well-known/oauth-authorization-server";

    /** The grant type of a device (RFC 8628). */
    public static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    /** The media type of a streamed chat (server-sent events). */
    public static final String EVENT_STREAM = "text/event-stream";

    private JoiProtocol() {
    }
}
