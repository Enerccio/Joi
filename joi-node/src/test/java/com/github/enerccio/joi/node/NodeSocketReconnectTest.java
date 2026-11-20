package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.Welcome;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node connects again when the connection is lost, as resumed, sends what the user said meanwhile, and does not
 * connect again after a close code that ends it.
 */
class NodeSocketReconnectTest {

    @Test
    void itConnectsAgainAsResumedUntilTheServerSaysItIsOver() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10_000);
            NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
                @Override
                public void disconnected(int code, String reason) {
                    seen.add("disconnected");
                }

                @Override
                public void reconnected(Welcome welcome) {
                    seen.add("reconnected " + welcome.session());
                }

                @Override
                public void closed(int code, String reason) {
                    seen.add("closed " + code);
                }

                @Override
                public void failed(String ref, com.github.enerccio.joi.protocol.http.ApiError error) {
                    seen.add("failed " + error.code());
                }
            });
            Hello hello = new Hello(JoiProtocol.NODE_VERSION, "n1", "desk", "desktop", "test", "j1", List.of(), null, null);
            URI uri = URI.create("ws://127.0.0.1:" + server.getLocalPort() + JoiProtocol.NODE);
            var welcomed = socket.connect(uri, null, hello, new Manifest(1, "macos", "aarch64", null, List.of()));

            try (Peer first = Peer.accept(server)) {
                JsonObject said = first.frame();
                assertThat(said.get("type").getAsString()).isEqualTo("hello");
                assertThat(said.getAsJsonObject("data").get("resumed")).isNull();
                first.welcome("s1");
                assertThat(welcomed.get(10, TimeUnit.SECONDS).session()).isEqualTo("s1");
                socket.manifest(new Manifest(2, "macos", "aarch64", null, List.of()));
                assertThat(first.frame().get("type").getAsString()).isEqualTo("manifest");
                assertThat(first.frame().getAsJsonObject("data").get("version").getAsLong()).isEqualTo(2);
                // the server goes away without a word
            }
            waitFor(() -> seen.contains("disconnected"));
            // said while it is not connected: nothing is sent, nor kept to be sent later
            socket.say("Are you there?", true, false);
            assertThat(seen).contains("failed disconnected");

            try (Peer again = Peer.accept(server)) {
                JsonObject said = again.frame();
                assertThat(said.get("type").getAsString()).isEqualTo("hello");
                assertThat(said.getAsJsonObject("data").get("resumed").getAsBoolean()).isTrue();
                // what it knows of the connection it lost: which run of the server it was with, when and how it ended
                JsonObject resume = said.getAsJsonObject("data").getAsJsonObject("resume");
                assertThat(resume.get("serverStartedAt").getAsString()).isEqualTo("2026-11-20T10:00:00Z");
                assertThat(resume.get("lostAt").getAsString()).isNotBlank();
                assertThat(resume.get("closeCode").getAsInt()).isEqualTo(1006);
                again.welcome("s2");
                JsonObject manifest = again.frame();
                assertThat(manifest.get("type").getAsString()).isEqualTo("manifest");
                assertThat(manifest.getAsJsonObject("data").get("version").getAsLong()).isEqualTo(2);
                waitFor(() -> seen.contains("reconnected s2"));
                assertThat(socket.connected()).isTrue();
                again.close(4003, "The node connected again");
                waitFor(() -> seen.contains("closed 4003"));
            }
            socket.say("Hello?", true, false);
            assertThat(seen).containsExactly("disconnected", "failed disconnected", "reconnected s2", "closed 4003", "failed disconnected");
            server.setSoTimeout(3_000);
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
            socket.close();
        }
    }

    @Test
    void aTryNowDoesNotWaitForTheNextOne() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ServerSocket server = new ServerSocket(0);
        NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void retrying(long waitSeconds) {
                seen.add("retrying " + waitSeconds);
            }

            @Override
            public void connecting() {
                seen.add("connecting");
            }
        });
        Hello hello = new Hello(JoiProtocol.NODE_VERSION, "n1", "desk", "desktop", "test", "j1", List.of(), null, null);
        URI uri = URI.create("ws://127.0.0.1:" + server.getLocalPort() + JoiProtocol.NODE);
        var welcomed = socket.connect(uri, null, hello, new Manifest(1, "macos", "aarch64", null, List.of()));
        server.setSoTimeout(10_000);
        try (Peer first = Peer.accept(server)) {
            first.frame();
            first.welcome("s1");
            welcomed.get(10, TimeUnit.SECONDS);
            first.frame();
        }
        // nobody listens any more: the tries fail, and the wait grows (1 s, then 2 s)
        server.close();
        waitFor(() -> seen.contains("retrying 2"));
        long connecting = seen.stream().filter("connecting"::equals).count();
        long started = System.currentTimeMillis();
        socket.reconnectNow();
        waitFor(() -> seen.stream().filter("connecting"::equals).count() > connecting);
        assertThat(System.currentTimeMillis() - started).as("it did not wait the 2 s").isLessThan(1500);
        // the wait of the tries after it starts again from 1 s
        waitFor(() -> seen.stream().filter("retrying 1"::equals).count() == 2);
        socket.close();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("waited too long").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    /** The server's end of one WebSocket connection: just enough of RFC 6455 for text and close frames. */
    private static final class Peer implements AutoCloseable {

        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;

        private Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        static Peer accept(ServerSocket server) throws Exception {
            Socket socket = server.accept();
            socket.setSoTimeout(10_000);
            String key = null;
            for (String line; (line = readLine(socket)) != null && !line.isEmpty(); ) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).strip();
                }
            }
            byte[] sha = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(sha) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return new Peer(socket);
        }

        /** A line of the upgrade request, read byte by byte so that no frame is buffered away. */
        private static String readLine(Socket socket) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int b; (b = socket.getInputStream().read()) != -1; ) {
                if (b == '\n') {
                    return line.toString().strip();
                }
                line.append((char) b);
            }
            return line.isEmpty() ? null : line.toString();
        }

        /** The next text frame of the node (pings skipped). */
        JsonObject frame() throws IOException {
            while (true) {
                int head = in.readUnsignedByte();
                int second = in.readUnsignedByte();
                long length = second & 0x7f;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                byte[] mask = new byte[4];
                in.readFully(mask);
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
                if ((head & 0x0f) != 1) {
                    continue;
                }
                JsonObject frame = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!frame.get("type").getAsString().equals("ping")) {
                    return frame;
                }
            }
        }

        void welcome(String session) throws IOException {
            send(0x1, ("{\"type\":\"welcome\",\"ref\":\"hello\",\"data\":{\"protocol\":\"1.3\",\"server\":\"test\",\"version\":\"0\","
                    + "\"session\":\"" + session + "\",\"joi\":{\"id\":\"j1\",\"name\":\"Joi\"},\"heartbeatSeconds\":30,"
                    + "\"serverStartedAt\":\"2026-11-20T10:00:00Z\"}}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        void close(int code, String reason) throws IOException {
            byte[] text = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + text.length];
            payload[0] = (byte) (code >> 8);
            payload[1] = (byte) code;
            System.arraycopy(text, 0, payload, 2, text.length);
            send(0x8, payload);
        }

        private void send(int opcode, byte[] payload) throws IOException {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length);
            }
            out.write(payload);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
