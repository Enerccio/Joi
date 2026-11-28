package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.TaskInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The node answers the tasks that wait through the server's HTTP API (a fake server here). */
class ServerClientTasksTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            queries.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization")
                    + " [" + body + "]");
            String answer = status == 200 && this.body != null ? this.body : status == 200
                    ? "{\"id\":\"t1\",\"title\":\"Prague\",\"tool\":\"web_search\",\"status\":\"queued\",\"created\":\"2026-11-20T10:00:00Z\"}"
                    : "{\"code\":\"conflict\",\"message\":\"The task is not waiting\"}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ServerClient client() throws Exception {
        NodeSettings settings = new NodeSettings(dir);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        settings.node().server = url;
        settings.credentials().token = "tok";
        return new ServerClient(url, settings);
    }

    @Test
    void approvingOnceHasNoBodyAndApprovingAlwaysSaysSo() throws Exception {
        TaskInfo once = client().approveTask("j1", "t1", false);
        assertThat(once.status()).isEqualTo("queued");
        client().approveTask("j1", "t1", true);
        assertThat(calls.get(0)).isEqualTo("POST /api/v1/jois/j1/tasks/t1/approve Bearer tok []");
        JsonObject body = JsonParser.parseString(calls.get(1).substring(calls.get(1).indexOf('[') + 1, calls.get(1).length() - 1)).getAsJsonObject();
        assertThat(body.get("always").getAsBoolean()).isTrue();
    }

    @Test
    void theHistorySlidesBackByTheFirstIdAndCarriesTheNewFields() throws Exception {
        body = "{\"messages\":[{\"id\":\"m1\",\"sentAt\":\"2026-11-20T10:00:00Z\",\"type\":\"user\",\"text\":\"Hi\",\"reply\":\"Hello\","
                + "\"toolImages\":[\"p1\"],\"summarized\":true}],\"more\":true}";
        com.github.enerccio.joi.protocol.http.ChatHistory page = client().history("j1", 500, "m 9");
        assertThat(calls.getFirst()).startsWith("GET /api/v1/jois/j1/chat ");
        assertThat(queries.getFirst()).isEqualTo("limit=500&before=m+9");
        assertThat(page.more()).isTrue();
        assertThat(page.messages().getFirst().isSummarized()).isTrue();
        assertThat(page.messages().getFirst().toolImages()).containsExactly("p1");
        client().history("j1", 500);
        assertThat(queries.get(1)).isEqualTo("limit=500");
    }

    @Test
    void anImageOfTheChatIsDownloadedOnceAndAStrangeIdIsRefused() throws Exception {
        body = "jpeg-bytes";
        Path first = client().image("abc-123");
        assertThat(java.nio.file.Files.readString(first)).isEqualTo("jpeg-bytes");
        assertThat(calls.getFirst()).isEqualTo("GET /api/v1/images/abc-123 Bearer tok []");
        // from the cache the second time
        assertThat(client().image("abc-123")).isEqualTo(first);
        assertThat(calls).hasSize(1);
        assertThatThrownBy(() -> client().image("../secret")).isInstanceOf(java.io.IOException.class);
        status = 404;
        assertThatThrownBy(() -> client().image("gone")).isInstanceOf(ServerClient.ServerException.class);
        assertThat(java.nio.file.Files.exists(dir.resolve("cache/images/gone.jpg"))).isFalse();
    }

    @Test
    void anImageIsStreamedToTheServerAndItsIdIsGivenBack() throws Exception {
        body = "{\"id\":\"i1\",\"mimeType\":\"image/jpeg\",\"size\":120,\"width\":8,\"height\":6}";
        Path file = dir.resolve("photo.jpg");
        java.nio.file.Files.writeString(file, "image-bytes");

        com.github.enerccio.joi.protocol.http.ImageUpload upload = client().uploadImage("j1", file);

        assertThat(upload.id()).isEqualTo("i1");
        assertThat(upload.width()).isEqualTo(8);
        assertThat(calls.get(0)).isEqualTo("POST /api/v1/jois/j1/images Bearer tok [image-bytes]");
        status = 400;
        assertThatThrownBy(() -> client().uploadImage("j1", file)).isInstanceOf(ServerClient.ServerException.class);
    }

    @Test
    void aDenialCarriesTheReasonAndAnAnsweredTaskIsAConflict() throws Exception {
        client().denyTask("j1", "t1", "Not now");
        assertThat(calls.get(0)).startsWith("POST /api/v1/jois/j1/tasks/t1/deny").contains("Not now");
        status = 409;
        assertThatThrownBy(() -> client().approveTask("j1", "t1", false))
                .isInstanceOfSatisfying(ServerClient.ServerException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("conflict");
                });
    }

    @Test
    void theTasksAndTheToolsApprovedAheadOfTimeAreReadAndChangedThroughTheApi() throws Exception {
        body = "{\"tasks\":[{\"id\":\"t1\",\"title\":\"Prague\",\"tool\":\"web_search\",\"status\":\"waiting\",\"created\":\"2026-11-20T10:00:00Z\"}]}";
        assertThat(client().tasks("j1", "waiting").tasks()).extracting(TaskInfo::id).containsExactly("t1");
        assertThat(calls.get(0)).startsWith("GET /api/v1/jois/j1/tasks Bearer tok");

        body = "{\"tools\":[{\"tool\":\"web_search\",\"name\":\"web_search\",\"description\":\"Search the web\"}]}";
        assertThat(client().autoApproved("j1").tools()).extracting(a -> a.tool()).containsExactly("web_search");
        client().autoApprove("j1", "web_search", true);
        client().autoApprove("j1", "web_search", false);
        assertThat(calls.get(2)).startsWith("PUT /api/v1/jois/j1/auto-approved/web_search Bearer tok");
        assertThat(calls.get(3)).startsWith("DELETE /api/v1/jois/j1/auto-approved/web_search Bearer tok");
    }
}
