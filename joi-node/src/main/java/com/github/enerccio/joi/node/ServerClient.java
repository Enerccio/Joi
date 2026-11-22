package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AutoApprovalList;
import com.github.enerccio.joi.protocol.http.BodyInfo;
import com.github.enerccio.joi.protocol.http.ChatHistory;
import com.github.enerccio.joi.protocol.http.Download;
import com.github.enerccio.joi.protocol.http.JoiInfo;
import com.github.enerccio.joi.protocol.http.JoiList;
import com.github.enerccio.joi.protocol.http.Me;
import com.github.enerccio.joi.protocol.http.ServerInfo;
import com.github.enerccio.joi.protocol.http.TaskApproval;
import com.github.enerccio.joi.protocol.http.TaskDenial;
import com.github.enerccio.joi.protocol.http.TaskInfo;
import com.github.enerccio.joi.protocol.http.TaskList;
import com.github.enerccio.joi.node.local.UnixHttp;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.LocalToken;
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
import java.util.LinkedHashMap;
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
    /** The server's local socket when it is on this machine ({@code unix:/path/to/socket}), else null. */
    private final UnixHttp unix;

    /** The prefix of the address of a server on this machine, reached over its Unix domain socket. */
    public static final String UNIX = "unix:";

    /**
     * Held while credentials are made again (a refresh, a local token), by every client of the process: they share the settings, and
     * a second refresh at once would make the first one's token useless. Not the settings' own lock, which the UI also takes.
     */
    private static final Object REFRESHING = new Object();

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
     * @param server   {@code http://host:port}, without a path; or {@code unix:/path/to/socket} for a server on this machine, reached
     *                 over its local socket, where the node needs no key and no login ({@link #signInLocal})
     * @param settings where the credentials are
     */
    public ServerClient(String server, NodeSettings settings) {
        this.server = server.replaceAll("/+$", "");
        this.settings = settings;
        this.unix = this.server.startsWith(UNIX) ? new UnixHttp(Path.of(this.server.substring(UNIX.length()))) : null;
    }

    /** Whether the server is on this machine, reached over its local socket. */
    public boolean local() {
        return unix != null;
    }

    /** The local socket of the server, null when it is reached over the network. */
    public Path localSocket() {
        return unix == null ? null : unix.socket();
    }

    public String server() {
        return server;
    }

    /** The node WebSocket's URL ({@code ws://} or {@code wss://}). */
    public URI nodeSocket() {
        return URI.create(unix != null ? "ws://localhost" + JoiProtocol.NODE : server.replaceFirst("^http", "ws") + JoiProtocol.NODE);
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
        // one refresh at a time for all clients of these settings: a second one would make the first one's token useless (a local
        // token is replaced, a refresh token is used up, and refreshing with a used one ends the pairing)
        synchronized (REFRESHING) {
            NodeSettings.Credentials credentials = settings.credentials();
            if (credentials.token == null && canRefresh() && (credentials.accessToken == null
                    || System.currentTimeMillis() > credentials.accessExpires - 60_000)) {
                refresh();
            }
            return credentials.authorization();
        }
    }

    /**
     * After a 401 to a call made with {@code used}: refreshes, unless another call refreshed meanwhile (the credentials are no longer
     * {@code used}), and then the call is only made again with the new ones.
     *
     * @return whether to make the call again
     */
    private boolean refreshAfter(String used) throws IOException, InterruptedException {
        synchronized (REFRESHING) {
            if (!canRefresh()) {
                return false;
            }
            if (java.util.Objects.equals(used, settings.credentials().authorization())) {
                refresh();
            }
            return true;
        }
    }

    /**
     * Allows a task of hers that waits (since 1.7); with {@code always} also the tool from now on, which the server keeps.
     * A task that was answered already is a {@link ServerException} 409 ({@code conflict}).
     */
    public TaskInfo approveTask(String joi, String task, boolean always) throws IOException, InterruptedException {
        return postJson(JoiProtocol.API + "/jois/" + joi + "/tasks/" + task + "/approve", always ? new TaskApproval(true) : null, TaskInfo.class);
    }

    /** Does not allow a task of hers that waits; she is told, with the reason when there is one. */
    public TaskInfo denyTask(String joi, String task, String reason) throws IOException, InterruptedException {
        return postJson(JoiProtocol.API + "/jois/" + joi + "/tasks/" + task + "/deny", reason == null ? null : new TaskDenial(reason), TaskInfo.class);
    }

    /** Her tasks, the newest first; {@code status} ({@code waiting}...) limits them, null for all (since 1.6). */
    public TaskList tasks(String joi, String status) throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/jois/" + joi + "/tasks" + (status == null ? "" : "?status=" + URLEncoder.encode(status, StandardCharsets.UTF_8)),
                TaskList.class, true);
    }

    /** The tools the user approved ahead of time for her (since 1.7). */
    public AutoApprovalList autoApproved(String joi) throws IOException, InterruptedException {
        return get(JoiProtocol.API + "/jois/" + joi + "/auto-approved", AutoApprovalList.class, true);
    }

    /**
     * Approves a tool ahead of time ({@code on}: its tasks are approved without asking, the ones that wait now too), or takes
     * that back (they ask again). The server keeps it. Answers the tools approved now.
     */
    public AutoApprovalList autoApprove(String joi, String tool, boolean on) throws IOException, InterruptedException {
        return callJson(on ? "PUT" : "DELETE", JoiProtocol.API + "/jois/" + joi + "/auto-approved/" + URLEncoder.encode(tool, StandardCharsets.UTF_8),
                null, AutoApprovalList.class);
    }

    private <T> T postJson(String path, Object body, Class<T> type) throws IOException, InterruptedException {
        return callJson("POST", path, body, type);
    }

    private <T> T callJson(String method, String path, Object body, Class<T> type) throws IOException, InterruptedException {
        String used = settings.credentials().authorization();
        Answer response = sendJson(method, path, body, used);
        if (response.status() == 401 && refreshAfter(used)) {
            response = sendJson(method, path, body, settings.credentials().authorization());
        }
        return answer(response, type);
    }

    private Answer sendJson(String method, String path, Object body, String authorization) throws IOException, InterruptedException {
        return exchange(method, path, body == null ? null : "application/json", authorization,
                body == null ? null : GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    /** What the server answered, read whole. */
    private record Answer(int status, String body) {
    }

    /** One call, over the network or the local socket. */
    private Answer exchange(String method, String path, String contentType, String authorization, byte[] body)
            throws IOException, InterruptedException {
        if (unix != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (contentType != null) {
                headers.put("Content-Type", contentType);
            }
            if (authorization != null) {
                headers.put("Authorization", authorization);
            }
            UnixHttp.Reply reply = unix.send(method, path, headers, body, Duration.ofSeconds(30));
            return new Answer(reply.status(), reply.body());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Answer(response.statusCode(), response.body());
    }

    private <T> T get(String path, Class<T> type, boolean authorized) throws IOException, InterruptedException {
        String used = authorized ? settings.credentials().authorization() : null;
        Answer response = send(path, used);
        if (response.status() == 401 && authorized && refreshAfter(used)) {
            response = send(path, settings.credentials().authorization());
        }
        return answer(response, type);
    }

    private Answer send(String path, String authorization) throws IOException, InterruptedException {
        return exchange("GET", path, null, authorization, null);
    }

    private static <T> T answer(Answer response, Class<T> type) throws ServerException {
        if (response.status() / 100 == 2) {
            return GSON.fromJson(response.body(), type);
        }
        ApiError error = null;
        try {
            error = GSON.fromJson(response.body(), ApiError.class);
        } catch (JsonParseException e) {
            // not ours: the status says enough
        }
        throw new ServerException(response.status(), error == null ? "http_" + response.status() : error.code(),
                error == null || error.message() == null ? "HTTP " + response.status() : error.message());
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
        if (file.sha256() == null || !file.sha256().matches("[0-9a-fA-F]{64}")) {
            // it names the file in the cache: anything but a hash could lead out of it
            throw new IOException("The server gave no SHA-256 for " + file.url());
        }
        Path cache = settings.cache();
        Files.createDirectories(cache);
        String extension = file.url().replaceAll(".*/", "").replaceAll("[^a-z]", "");
        Path target = cache.resolve(file.sha256() + "." + (extension.isEmpty() ? "bin" : extension));
        if (Files.exists(target) && Files.size(target) == file.size()) {
            return target;
        }
        String authorization = authorization();
        int status;
        InputStream content;
        if (unix != null) {
            // the server names a file by an address it was reached at: here only its path and query count
            URI uri = URI.create(file.url());
            String path = file.url().startsWith("http") ? uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) : file.url();
            UnixHttp.Stream stream = unix.open("GET", path, authorization == null ? Map.of() : Map.of("Authorization", authorization), null);
            status = stream.status();
            content = stream.body();
        } else {
            String url = file.url().startsWith("http") ? file.url() : server + file.url();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            content = response.body();
        }
        if (status != 200) {
            try (InputStream body = content) {
                answer(new Answer(status, new String(body.readAllBytes(), StandardCharsets.UTF_8)), Void.class);
            }
        }
        Path temp = Files.createTempFile(cache, "download", ".part");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(content, sha); OutputStream out = Files.newOutputStream(temp)) {
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

    // ----------------------------------------------------------------------------------------------------------------
    // OAuth device grant (RFC 8628)
    // ----------------------------------------------------------------------------------------------------------------

    /** Asks for a code the user approves in the server's UI. */
    public DeviceAuthorization startDeviceGrant(String nodeName) throws IOException, InterruptedException {
        Answer response = post(JoiProtocol.DEVICE_AUTHORIZATION,
                Map.of("client_id", CLIENT_ID, "client_name", "Joi node " + nodeName));
        if (response.status() != 200) {
            throw oauthError(response);
        }
        return GSON.fromJson(response.body(), DeviceAuthorization.class);
    }

    /**
     * Asks once whether the user approved the code: true when the tokens are stored, false while it waits
     * ({@code authorization_pending}, {@code slow_down}); a refused or expired code throws.
     */
    public boolean pollDeviceGrant(String deviceCode) throws IOException, InterruptedException {
        Answer response = post(JoiProtocol.TOKEN, Map.of("grant_type", JoiProtocol.DEVICE_CODE_GRANT,
                "device_code", deviceCode, "client_id", CLIENT_ID));
        if (response.status() == 200) {
            store(GSON.fromJson(response.body(), TokenResponse.class));
            return true;
        }
        ServerException error = oauthError(response);
        if (error.code().equals("authorization_pending") || error.code().equals("slow_down")) {
            return false;
        }
        throw error;
    }

    /** Whether the credentials can be made again without the user: an OAuth pair has a refresh token, a server on this machine has its socket. */
    private boolean canRefresh() throws IOException {
        return unix != null || settings.credentials().refreshToken != null;
    }

    /**
     * Gets the token of a node on the server's own machine ({@code POST /oauth/local}, over its local socket: no key, no login) and keeps it.
     * It expires; the node asks again, so there is nothing to approve and nothing to refresh.
     *
     * @throws ServerException {@code no_user} (409) when nobody has an account yet: the first one is made in the web UI
     * @throws IllegalStateException the server is not reached over a local socket
     */
    public void signInLocal() throws IOException, InterruptedException {
        if (unix == null) {
            throw new IllegalStateException("The server is not on this machine: " + server);
        }
        // with the refreshes: a new local token replaces the one another call is using
        synchronized (REFRESHING) {
            NodeSettings.Node node = settings.node();
            Answer response = post(JoiProtocol.LOCAL, Map.of("client_id", CLIENT_ID + "-" + node.nodeId, "client_name", "Joi node " + node.name));
            if (response.status() != 200) {
                throw oauthError(response);
            }
            LocalToken token = GSON.fromJson(response.body(), LocalToken.class);
            NodeSettings.Credentials credentials = settings.credentials();
            credentials.token = null;
            credentials.accessToken = token.accessToken();
            credentials.refreshToken = null;
            credentials.accessExpires = System.currentTimeMillis() + token.expiresIn() * 1000;
            settings.saveCredentials();
        }
    }

    private void refresh() throws IOException, InterruptedException {
        if (unix != null) {
            signInLocal();
            return;
        }
        NodeSettings.Credentials credentials = settings.credentials();
        Answer response = post(JoiProtocol.TOKEN, Map.of("grant_type", "refresh_token",
                "refresh_token", credentials.refreshToken, "client_id", CLIENT_ID));
        if (response.status() != 200) {
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

    private Answer post(String path, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return exchange("POST", path, "application/x-www-form-urlencoded", null, body.getBytes(StandardCharsets.UTF_8));
    }

    private static ServerException oauthError(Answer response) {
        OAuthError error = null;
        try {
            error = GSON.fromJson(response.body(), OAuthError.class);
        } catch (JsonParseException e) {
            // not OAuth's
        }
        return new ServerException(response.status(), error == null || error.error() == null ? "http_" + response.status() : error.error(),
                error == null || error.errorDescription() == null ? "HTTP " + response.status() : error.errorDescription());
    }
}
