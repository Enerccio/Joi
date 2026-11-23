package com.github.enerccio.joi.node.local;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.WebSocket;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The HTTP and WebSocket clients over a Unix domain socket, against a small server of their own on one. */
class UnixSocketTest {

    private Path dir;
    private Path socket;
    private ServerSocketChannel server;
    private final AtomicReference<String> request = new AtomicReference<>();

    @BeforeEach
    void listen() throws IOException {
        // the path of a Unix socket is short (about 100 bytes): the system's temp folder on a Mac is not
        Path base = Files.isDirectory(Path.of("/tmp")) ? Path.of("/tmp") : Path.of(System.getProperty("java.io.tmpdir"));
        dir = Files.createTempDirectory(base, "joi");
        socket = dir.resolve("a.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    /** Serves one connection on a thread of its own. */
    private void serve(Connection handler) {
        Thread thread = new Thread(() -> {
            try (SocketChannel channel = server.accept()) {
                handler.handle(Channels.newInputStream(channel), Channels.newOutputStream(channel));
            } catch (Exception e) {
                // the test sees what the client got
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private interface Connection {
        void handle(InputStream in, OutputStream out) throws Exception;
    }

    /** The head of a request, up to and with the empty line; the body is the Content-Length's. */
    private static String readRequest(InputStream in, ByteArrayOutputStream bodyOut) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                in.readNBytes(Integer.parseInt(line.substring(15).strip()));
                bodyOut.write(in.readNBytes(0));
            }
        }
        return text;
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aCallGetsItsAnswerWhateverWayTheBodyIsSent() throws Exception {
        serve((in, out) -> {
            // the body of a POST is read by the server here, so Content-Length is checked by what the client wrote
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                head.write(in.read());
            }
            String text = head.toString(StandardCharsets.ISO_8859_1);
            int length = Integer.parseInt(text.replaceAll("(?is).*content-length: *(\\d+).*", "$1"));
            request.set(text + new String(in.readNBytes(length), StandardCharsets.UTF_8));
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}\n").getBytes(StandardCharsets.UTF_8));
        });
        UnixHttp.Reply reply = new UnixHttp(socket).send("POST", "/oauth/local?x=1", Map.of("Authorization", "Bearer abc"),
                "client_id=desk".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("{\"ok\":true}\n".substring(0, 11));
        assertThat(reply.headers()).containsEntry("content-type", "application/json");
        assertThat(request.get()).startsWith("POST /oauth/local?x=1 HTTP/1.1\r\n")
                .contains("Host: localhost\r\n", "Connection: close\r\n", "Authorization: Bearer abc\r\n", "Content-Length: 14\r\n")
                .endsWith("client_id=desk");
    }

    @Test
    void aFileIsStreamedAsTheBodyWithItsLength() throws Exception {
        byte[] content = new byte[100_000];
        new java.util.Random(1).nextBytes(content);
        Path file = Files.createTempFile("joi-image", ".bin");
        Files.write(file, content);
        java.util.concurrent.atomic.AtomicReference<byte[]> got = new java.util.concurrent.atomic.AtomicReference<>();
        serve((in, out) -> {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                head.write(in.read());
            }
            int length = Integer.parseInt(head.toString(StandardCharsets.ISO_8859_1).replaceAll("(?is).*content-length: *(\\d+).*", "$1"));
            got.set(in.readNBytes(length));
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
        });

        UnixHttp.Reply reply = new UnixHttp(socket).sendFile("POST", "/api/v1/jois/j/images", Map.of(), file, Duration.ofSeconds(5));

        Files.delete(file);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(got.get()).isEqualTo(content);
    }

    @Test
    void anAnswerInChunksOrUntilTheConnectionClosesIsRead() throws Exception {
        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\nX-Trailer: 1\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        });
        assertThat(new UnixHttp(socket).send("GET", "/a", Map.of(), null, Duration.ofSeconds(5)).body()).isEqualTo("hello, world");

        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write("HTTP/1.1 404 Not Found\r\n\r\nno length".getBytes(StandardCharsets.UTF_8));
        });
        UnixHttp.Reply notFound = new UnixHttp(socket).send("GET", "/b", Map.of(), null, Duration.ofSeconds(5));
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.body()).isEqualTo("no length");

        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write("HTTP/1.1 304 Not Modified\r\nETag: \"x\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        });
        UnixHttp.Reply notModified = new UnixHttp(socket).send("GET", "/c", Map.of(), null, Duration.ofSeconds(5));
        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
    }

    @Test
    void aBodyIsStreamedForADownload() throws Exception {
        byte[] file = new byte[300_000];
        for (int i = 0; i < file.length; i++) {
            file[i] = (byte) (i * 31);
        }
        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + file.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(file);
        });
        try (UnixHttp.Stream stream = new UnixHttp(socket).open("GET", "/f", Map.of(), null)) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.body().readAllBytes()).isEqualTo(file);
        }
    }

    @Test
    void aServerThatIsNotThereOrDoesNotAnswerIsAnError() throws Exception {
        assertThatThrownBy(() -> new UnixHttp(dir.resolve("none.sock")).send("GET", "/", Map.of(), null, Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class);
        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            Thread.sleep(3000);
        });
        assertThatThrownBy(() -> new UnixHttp(socket).send("GET", "/", Map.of(), null, Duration.ofMillis(300)))
                .isInstanceOf(IOException.class).hasMessageContaining("did not answer");
        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write("SSH-2.0-nope\r\n".getBytes(StandardCharsets.UTF_8));
        });
        assertThatThrownBy(() -> new UnixHttp(socket).send("GET", "/", Map.of(), null, Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP");
    }

    @Test
    void aRequestThatCouldSmuggleAHeaderIsRefused() {
        UnixHttp http = new UnixHttp(socket);
        assertThatThrownBy(() -> http.send("GET", "/a b", Map.of(), null, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> http.send("GET", "/a", Map.of("X", "1\r\nEvil: 1"), null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> http.send("GET", "/a", Map.of("X\r\nEvil", "1"), null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the WebSocket
    // ----------------------------------------------------------------------------------------------------------------

    /** Reads the handshake and answers it; returns the request. */
    private static String accept(InputStream in, OutputStream out) throws Exception {
        String head = readRequest(in, new ByteArrayOutputStream());
        String key = head.replaceAll("(?is).*sec-websocket-key: *([^\r\n]+).*", "$1").strip();
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return head;
    }

    private static void frame(OutputStream out, int opcode, boolean fin, byte[] payload) throws IOException {
        out.write((fin ? 0x80 : 0) | opcode);
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

    /** A frame of the client: its opcode and unmasked payload (a client must mask, so an unmasked one fails the test). */
    private static Object[] clientFrame(DataInputStream in) throws IOException {
        int first = in.readUnsignedByte();
        int second = in.readUnsignedByte();
        assertThat(second & 0x80).as("the client masks its frames").isNotZero();
        long length = second & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        }
        byte[] mask = new byte[4];
        in.readFully(mask);
        byte[] payload = new byte[(int) length];
        in.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Object[]{first & 0x0F, payload};
    }

    private static final class Events implements WebSocket.Listener {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            seen.add("text " + data + " " + last);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            seen.add("close " + statusCode + " " + reason);
            ended.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            seen.add("error");
            ended.countDown();
        }
    }

    @Test
    void textGoesBothWaysAPingIsAnsweredAndTheServerClosesWithItsCode() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        serve((in, out) -> {
            String head = accept(in, out);
            got.add(head.contains("Authorization: Bearer joil_x") ? "authorized" : "anonymous");
            got.add(head.startsWith("GET /api/v1/node HTTP/1.1") ? "path" : "other path");
            DataInputStream data = new DataInputStream(in);
            // a message in two frames, the word "wör" in UTF-8 split in the middle of its letter
            byte[] word = "wör ld".getBytes(StandardCharsets.UTF_8);
            frame(out, 1, false, java.util.Arrays.copyOfRange(word, 0, 2));
            frame(out, 0, true, java.util.Arrays.copyOfRange(word, 2, word.length));
            frame(out, 9, true, "are you there".getBytes(StandardCharsets.UTF_8));
            Object[] pong = clientFrame(data);
            got.add("opcode " + pong[0] + " " + new String((byte[]) pong[1], StandardCharsets.UTF_8));
            Object[] text = clientFrame(data);
            got.add("opcode " + text[0] + " " + new String((byte[]) text[1], StandardCharsets.UTF_8));
            // a long one, longer than 125 bytes
            Object[] longer = clientFrame(data);
            got.add("opcode " + longer[0] + " " + ((byte[]) longer[1]).length);
            frame(out, 8, true, new byte[]{(byte) (4001 >> 8), (byte) 4001, 'n', 'o'});
            Object[] close = clientFrame(data);
            got.add("opcode " + close[0]);
        });
        Events events = new Events();
        WebSocket ws = UnixWebSocket.connectAsync(socket, URI.create("ws://localhost/api/v1/node"), Map.of("Authorization", "Bearer joil_x"), events)
                .get(5, TimeUnit.SECONDS);
        // the pong goes first: the server waits for it
        Thread.sleep(300);
        ws.sendText("héllo", true).get(5, TimeUnit.SECONDS);
        ws.sendText("x".repeat(300), true).get(5, TimeUnit.SECONDS);
        assertThat(events.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.seen).containsExactly("text wör ld true", "close 4001 no");
        Thread.sleep(300);
        assertThat(got).containsExactly("authorized", "path", "opcode 10 are you there", "opcode 1 héllo", "opcode 1 300", "opcode 8");
        assertThat(ws.isInputClosed()).isTrue();
    }

    @Test
    void aWebSocketThatTheServerRefusesOrCutsIsAnError() throws Exception {
        serve((in, out) -> {
            readRequest(in, new ByteArrayOutputStream());
            out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        });
        assertThatThrownBy(() -> UnixWebSocket.connectAsync(socket, URI.create("ws://localhost/api/v1/node"), Map.of(), new Events())
                .get(5, TimeUnit.SECONDS)).hasMessageContaining("401");

        serve((in, out) -> {
            accept(in, out);
            // gone without a close frame
        });
        Events events = new Events();
        UnixWebSocket.connectAsync(socket, URI.create("ws://localhost/api/v1/node"), Map.of(), events).get(5, TimeUnit.SECONDS);
        assertThat(events.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.seen).containsExactly("error");
    }

    @Test
    void closingFromThisEndSendsTheCloseAndEndsWhenTheServerAnswers() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        serve((in, out) -> {
            accept(in, out);
            Object[] close = clientFrame(new DataInputStream(in));
            byte[] payload = (byte[]) close[1];
            got.add("opcode " + close[0] + " " + (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) + " " + new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8));
            frame(out, 8, true, new byte[]{payload[0], payload[1]});
        });
        Events events = new Events();
        WebSocket ws = UnixWebSocket.connectAsync(socket, URI.create("ws://localhost/x"), Map.of(), events).get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        assertThat(events.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(got).containsExactly("opcode 8 1000 bye");
        assertThat(events.seen).containsExactly("close 1000 ");
        assertThat(ws.isOutputClosed()).isTrue();
    }
}
