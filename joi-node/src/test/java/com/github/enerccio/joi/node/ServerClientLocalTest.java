package com.github.enerccio.joi.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The node signs in to a server on its own machine over the server's Unix socket: no key, no login (a fake server on a socket here). */
class ServerClientLocalTest {

    @TempDir
    Path home;

    private Path dir;
    private Path socket;
    private ServerSocketChannel server;
    private Thread acceptor;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile int issued;

    @BeforeEach
    void listen() throws IOException {
        // the path of a Unix socket is short (about 100 bytes): the system's temp folder on a Mac is not
        Path base = Files.isDirectory(Path.of("/tmp")) ? Path.of("/tmp") : Path.of(System.getProperty("java.io.tmpdir"));
        dir = Files.createTempDirectory(base, "joi");
        socket = dir.resolve("a.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        acceptor = new Thread(() -> {
            while (server.isOpen()) {
                try (SocketChannel channel = server.accept()) {
                    answer(Channels.newInputStream(channel), Channels.newOutputStream(channel));
                } catch (Exception e) {
                    // closed with the test
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    private void answer(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            head.write(b);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        String lengthLine = text.lines().filter(l -> l.toLowerCase().startsWith("content-length:")).findFirst().orElse("content-length: 0");
        String body = new String(in.readNBytes(Integer.parseInt(lengthLine.substring(15).strip())), StandardCharsets.UTF_8);
        String requestLine = text.lines().findFirst().orElse("");
        String authorization = text.lines().filter(l -> l.toLowerCase().startsWith("authorization:")).findFirst().orElse("authorization:").substring(14).strip();
        calls.add(requestLine + " [" + authorization + "] [" + body + "]");
        String json;
        int code = 200;
        if (requestLine.startsWith("POST /oauth/local")) {
            if (status != 200) {
                code = status;
                json = "{\"error\":\"no_user\",\"error_description\":\"Nobody has an account yet\"}";
            } else {
                json = "{\"access_token\":\"joil_" + (++issued) + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
            }
        } else {
            json = "{\"id\":\"u1\",\"username\":\"me\",\"name\":\"Me\"}";
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private NodeSettings settings;

    private ServerClient client() throws IOException {
        settings = new NodeSettings(home);
        ServerClient client = new ServerClient(ServerClient.UNIX + socket, settings);
        settings.node().server = client.server();
        return client;
    }

    @Test
    void theSocketIsTheServersAddress() throws Exception {
        ServerClient client = client();
        assertThat(client.local()).isTrue();
        assertThat(client.localSocket()).isEqualTo(socket);
        assertThat(client.nodeSocket().toString()).isEqualTo("ws://localhost/api/v1/node");
        assertThat(new ServerClient("http://localhost:8080/", new NodeSettings(home)).local()).isFalse();
    }

    @Test
    void theNodeGetsATokenWithNoKeyAndUsesIt() throws Exception {
        ServerClient client = client();
        client.signInLocal();
        assertThat(calls).singleElement().satisfies(call -> assertThat(call)
                .startsWith("POST /oauth/local HTTP/1.1 []")
                .contains("client_id=joi-node-"));
        assertThat(client.authorization()).isEqualTo("Bearer joil_1");
        client.me();
        assertThat(calls.get(1)).startsWith("GET /api/v1/me HTTP/1.1 [Bearer joil_1]");
        // nothing to refresh: the token is kept without a refresh token
        assertThat(new NodeSettings(home).credentials().refreshToken).isNull();
    }

    @Test
    void anExpiredTokenIsAskedForAgainWithoutAnyone() throws Exception {
        ServerClient client = client();
        client.signInLocal();
        // an hour later: the token is as good as expired, the node asks again and uses the new one
        settings.credentials().accessExpires = System.currentTimeMillis() - 1000;
        assertThat(client.authorization()).isEqualTo("Bearer joil_2");
        assertThat(calls).hasSize(2);
        // and a token that is still good is not asked for again
        assertThat(client.authorization()).isEqualTo("Bearer joil_2");
        assertThat(calls).hasSize(2);
    }

    @Test
    void noAccountYetIsSaidByItsCode() throws Exception {
        status = 409;
        assertThatThrownBy(() -> client().signInLocal())
                .isInstanceOfSatisfying(ServerClient.ServerException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("no_user");
                });
    }

    @Test
    void aServerOnTheNetworkHasNoLocalSignIn() {
        ServerClient client = new ServerClient("http://127.0.0.1:1", new NodeSettings(home));
        assertThatThrownBy(client::signInLocal).isInstanceOf(IllegalStateException.class);
    }
}
