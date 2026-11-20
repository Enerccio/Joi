package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.BodyInfo;
import com.github.enerccio.joi.protocol.http.ChatHistory;
import com.github.enerccio.joi.protocol.http.Download;
import com.github.enerccio.joi.protocol.http.JoiInfo;
import com.github.enerccio.joi.protocol.http.JoiList;
import com.github.enerccio.joi.protocol.http.Me;
import com.github.enerccio.joi.protocol.http.ServerInfo;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

/**
 * The HTTP API of a Joi server as the node uses it ({@code openapi.yaml}): what the server offers, the user's
 * assistants, her body and its files (cached by their SHA-256, streamed to disk), and the OAuth device grant. The
 * credentials are the settings'; an expired OAuth access token is refreshed once and the call tried again.
 */
public class ServerClient {

    private static final Gson GSON = new Gson();
    /** The name the node gives itself in the device grant. */
    static final String CLIENT_ID = "joi-node";

    private final String server;
    private final NodeSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** A call the server refused: its status and its {@link ApiError}. */
    public static class ServerException extends IOException {
        private final int status;
        private final String code;

        public ServerException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param server   {@code http://host:port}, without a path
     * @param settings where the credentials are
     */
    public ServerClient(String server, NodeSettings settings) {
        this.server = server.replaceAll("/+$", "");
        this.settings = settings;
    }

    public String server() {
        return server;
    }

    /** The node WebSocket's URL ({@code ws://} or {@code wss://}). */
    public URI nodeSocket() {
        return URI.create(server.replaceFirst("^http", "ws") + JoiProtocol.NODE);
    }

    /** What the server is and how to authenticate (needs no credentials). */
    public ServerInfo info() throws IOException, InterruptedException {
        return get(JoiProtocol.API, ServerInfo.class, false);
    }

    public Me me() throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/me", Me.class, true);
    }

    public JoiList jois() throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/jois", JoiList.class, true);
    }

    public JoiInfo joi(String id) throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/jois/" + id, JoiInfo.class, true);
    }

    /** Her latest {@code limit} exchanges, oldest first (since 1.3). */
    public ChatHistory history(String joi, int limit) throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/jois/" + joi + "/chat?limit=" + limit, ChatHistory.class, true);
    }

    /** The body she is in, null for none. */
    public BodyInfo body(String joi) throws IOException, InterruptedException {
        try {
            return get(JoiProtocol.API + "/jois/" + joi + "/body", BodyInfo.class, true);
        } catch (ServerException e) {
            if (e.status() == 404 && "no_body".equals(e.code())) {
                return null;
            }
            throw e;
        }
    }

    /** The {@code Authorization} header now, refreshing an expired OAuth access token first; null for none. */
    public String authorization() throws IOException, InterruptedException {
        NodeSettings.Credentials credentials = settings.credentials();
        if (credentials.token == null && credentials.refreshToken != null
                && System.currentTimeMillis() > credentials.accessExpires - 60_000) {
            refresh();
        }
        return credentials.authorization();
    }

    private <T> T get(String path, Class<T> type, boolean authorized) throws IOException, InterruptedException {
        HttpResponse<String> response = send(path, authorized);
        if (response.statusCode() == 401 && authorized && settings.credentials().refreshToken != null) {
            refresh();
            response = send(path, true);
        }
        return answer(response, type);
    }

    private HttpResponse<String> send(String path, boolean authorized) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(30)).GET();
        String authorization = authorized ? settings.credentials().authorization() : null;
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static <T> T answer(HttpResponse<String> response, Class<T> type) throws ServerException {
        if (response.statusCode() / 100 == 2) {
            return GSON.fromJson(response.body(), type);
        }
        ApiError error = null;
        try {
            error = GSON.fromJson(response.body(), ApiError.class);
        } catch (JsonParseException e) {
            // not ours: the status says enough
        }
        throw new ServerException(response.statusCode(), error == null ? "http_" + response.statusCode() : error.code(),
                error == null || error.message() == null ? "HTTP " + response.statusCode() : error.message());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // files
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * The file, from the cache when it holds it (by its SHA-256), else downloaded into it. The download streams to a
     * temp file and is checked against the hash before it is kept.
     *
     * @param progress the bytes so far, as they come (may be null)
     */
    public Path download(Download file, LongConsumer progress) throws IOException, InterruptedException {
        Path cache = settings.cache();
        Files.createDirectories(cache);
        String extension = file.url().replaceAll(".*/", "").replaceAll("[^a-z]", "");
        Path target = cache.resolve(file.sha256() + "." + (extension.isEmpty() ? "bin" : extension));
        if (Files.exists(target) && Files.size(target) == file.size()) {
            return target;
        }
        String url = file.url().startsWith("http") ? file.url() : server + file.url();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        String authorization = authorization();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                answer(new StringResponse(response, new String(body.readAllBytes(), StandardCharsets.UTF_8)), Void.class);
            }
        }
        Path temp = Files.createTempFile(cache, "download", ".part");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(response.body(), sha); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = in.read(buffer)) >= 0; ) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (progress != null) {
                        progress.accept(total);
                    }
                }
            }
            String hash = HexFormat.of().formatHex(sha.digest());
            if (!hash.equalsIgnoreCase(file.sha256())) {
                throw new IOException("The file of " + file.url() + " is not what the server said (SHA-256 " + hash + ")");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** An error body read from a streamed answer, for {@link #answer}. */
    private record StringResponse(HttpResponse<InputStream> of, String body) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return of.statusCode();
        }

        @Override
        public HttpRequest request() {
            return of.request();
        }

        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return of.headers();
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return of.sslSession();
        }

        @Override
        public URI uri() {
            return of.uri();
        }

        @Override
        public HttpClient.Version version() {
            return of.version();
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // OAuth device grant (RFC 8628)
    // ----------------------------------------------------------------------------------------------------------------

    /** Asks for a code the user approves in the server's UI. */
    public DeviceAuthorization startDeviceGrant(String nodeName) throws IOException, InterruptedException {
        HttpResponse<String> response = post(JoiProtocol.DEVICE_AUTHORIZATION,
                Map.of("client_id", CLIENT_ID, "client_name", "Joi node " + nodeName));
        if (response.statusCode() != 200) {
            throw oauthError(response);
        }
        return GSON.fromJson(response.body(), DeviceAuthorization.class);
    }

    /**
     * Asks once whether the user approved the code: true when the tokens are stored, false while it waits
     * ({@code authorization_pending}, {@code slow_down}); a refused or expired code throws.
     */
    public boolean pollDeviceGrant(String deviceCode) throws IOException, InterruptedException {
        HttpResponse<String> response = post(JoiProtocol.TOKEN, Map.of("grant_type", JoiProtocol.DEVICE_CODE_GRANT,
                "device_code", deviceCode, "client_id", CLIENT_ID));
        if (response.statusCode() == 200) {
            store(GSON.fromJson(response.body(), TokenResponse.class));
            return true;
        }
        ServerException error = oauthError(response);
        if (error.code().equals("authorization_pending") || error.code().equals("slow_down")) {
            return false;
        }
        throw error;
    }

    private void refresh() throws IOException, InterruptedException {
        NodeSettings.Credentials credentials = settings.credentials();
        HttpResponse<String> response = post(JoiProtocol.TOKEN, Map.of("grant_type", "refresh_token",
                "refresh_token", credentials.refreshToken, "client_id", CLIENT_ID));
        if (response.statusCode() != 200) {
            // the pair is no longer good: the user pairs the node again
            credentials.accessToken = null;
            credentials.refreshToken = null;
            settings.saveCredentials();
            throw oauthError(response);
        }
        store(GSON.fromJson(response.body(), TokenResponse.class));
    }

    private void store(TokenResponse tokens) throws IOException {
        NodeSettings.Credentials credentials = settings.credentials();
        credentials.token = null;
        credentials.accessToken = tokens.accessToken();
        credentials.refreshToken = tokens.refreshToken();
        credentials.accessExpires = System.currentTimeMillis() + tokens.expiresIn() * 1000;
        settings.saveCredentials();
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static ServerException oauthError(HttpResponse<String> response) {
        OAuthError error = null;
        try {
            error = GSON.fromJson(response.body(), OAuthError.class);
        } catch (JsonParseException e) {
            // not OAuth's
        }
        return new ServerException(response.statusCode(), error == null || error.error() == null ? "http_" + response.statusCode() : error.error(),
                error == null || error.errorDescription() == null ? "HTTP " + response.statusCode() : error.errorDescription());
    }
}
