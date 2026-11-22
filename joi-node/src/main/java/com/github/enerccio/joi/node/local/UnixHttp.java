package com.github.enerccio.joi.node.local;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP/1.1 over a Unix domain socket: how a node on the server's own machine talks to the server's API without a port, a key or a
 * login (the socket is in the server's data folder; whoever can open it may read that folder). The JDK's {@code HttpClient} cannot
 * connect to such a socket, and what the node needs is small: a request with a body that is already in memory, an answer that is
 * read whole or streamed to a file, nothing else (no redirects, no TLS, no keep-alive: each request has its own connection and
 * says {@code Connection: close}, the socket being local and cheap). The node WebSocket over the same socket is
 * {@link UnixWebSocket}.
 */
public final class UnixHttp {

    /** The answer read whole; the header names are in lower case. */
    public record Reply(int status, Map<String, String> headers, String body) {
    }

    /** The answer with its body still to be read; closing it closes the connection. */
    public record Stream(int status, Map<String, String> headers, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 200;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Joi unix socket timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final Path socket;

    /** @param socket the Unix domain socket the server listens on ({@code joi.api.local-socket}) */
    public UnixHttp(Path socket) {
        this.socket = socket;
    }

    public Path socket() {
        return socket;
    }

    /**
     * One request and the whole answer.
     *
     * @param path    the path and query, starting with {@code /}
     * @param headers the headers to send besides the ones the request needs ({@code Host}, {@code Content-Length}...)
     * @param body    the body, null for none
     * @param timeout how long the whole call may take
     * @throws IOException the socket cannot be reached, or the answer is not HTTP, or it took too long
     */
    public Reply send(String method, String path, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        SocketChannel channel = connect(socket);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> cut = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            close(channel);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try (Stream stream = exchange(channel, method, path, headers, body)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stream.body().transferTo(out);
            return new Reply(stream.status(), stream.headers(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (timedOut.get()) {
                throw new IOException("The server did not answer within " + timeout.toSeconds() + " s", e);
            }
            throw e;
        } finally {
            cut.cancel(false);
            close(channel);
        }
    }

    /**
     * One request; the answer's body is read by the caller (a file that is streamed to the disk) and ends the connection when it is
     * closed. There is no timeout: a download takes as long as it takes.
     */
    public Stream open(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        SocketChannel channel = connect(socket);
        try {
            return exchange(channel, method, path, headers, body);
        } catch (IOException | RuntimeException e) {
            close(channel);
            throw e;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------

    static SocketChannel connect(Path socket) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return channel;
        } catch (IOException | RuntimeException e) {
            close(channel);
            throw e;
        }
    }

    static void close(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // closed
        }
    }

    private static Stream exchange(SocketChannel channel, String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append(token(method)).append(' ').append(target(path)).append(" HTTP/1.1\r\n");
        head.append("Host: localhost\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(token(header.getKey())).append(": ").append(value(header.getValue())).append("\r\n");
        }
        if (body != null || method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")) {
            head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = Channels.newOutputStream(channel);
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) {
            out.write(body);
        }
        out.flush();

        InputStream in = new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
        Head answer = readHead(in);
        while (answer.status / 100 == 1 && answer.status != 101) {
            // 100 Continue and the like: the real answer follows
            answer = readHead(in);
        }
        boolean none = method.equalsIgnoreCase("HEAD") || answer.status == 204 || answer.status == 304 || answer.status == 101;
        InputStream content;
        String encoding = answer.headers.getOrDefault("transfer-encoding", "");
        if (none) {
            content = InputStream.nullInputStream();
        } else if (encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            content = new Chunked(in);
        } else if (answer.headers.containsKey("content-length")) {
            long length;
            try {
                length = Long.parseLong(answer.headers.get("content-length").strip());
            } catch (NumberFormatException e) {
                throw new IOException("The server sent a Content-Length that is not a number");
            }
            content = new Bounded(in, length);
        } else {
            content = in;
        }
        InputStream closing = new java.io.FilterInputStream(content) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    channel.close();
                }
            }
        };
        return new Stream(answer.status, answer.headers, closing);
    }

    /** What the first lines of an answer say; also read by {@link UnixWebSocket}'s handshake. */
    record Head(int status, Map<String, String> headers) {
    }

    static Head readHead(InputStream in) throws IOException {
        String status = readLine(in);
        if (status == null || !status.startsWith("HTTP/")) {
            throw new IOException("The server did not answer with HTTP" + (status == null ? " (the connection closed)" : ""));
        }
        String[] parts = status.split(" ", 3);
        int code;
        try {
            code = Integer.parseInt(parts[1].strip());
        } catch (RuntimeException e) {
            throw new IOException("The status line of the server is not HTTP: " + status);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; ; i++) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("The connection closed in the headers of the answer");
            }
            if (line.isEmpty()) {
                break;
            }
            if (i >= MAX_HEADERS) {
                throw new IOException("The answer has too many headers");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.merge(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip(),
                        (a, b) -> a + ", " + b);
            }
        }
        return new Head(code, headers);
    }

    /** A line without its line end, null at the end of the stream before any byte. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            if (line.size() > MAX_LINE) {
                throw new IOException("A line of the answer is longer than " + MAX_LINE + " bytes");
            }
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    static String token(String text) {
        if (text == null || text.isEmpty() || !text.chars().allMatch(c -> c > ' ' && c < 127 && c != ':')) {
            throw new IllegalArgumentException("Not an HTTP token: " + text);
        }
        return text;
    }

    /** A header value that cannot end the header and start another (no line break). */
    static String value(String text) {
        if (text == null || text.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            throw new IllegalArgumentException("A header value must be one line");
        }
        return text;
    }

    private static String target(String path) {
        if (path == null || !path.startsWith("/") || path.chars().anyMatch(c -> c <= ' ' || c >= 127)) {
            throw new IllegalArgumentException("Not a path of a request: " + path);
        }
        return path;
    }

    /** The body of an answer with a {@code Content-Length}. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long left;

        Bounded(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new IOException("The connection closed before the whole body came");
            }
            left--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = in.read(buffer, offset, (int) Math.min(length, left));
            if (n < 0) {
                throw new IOException("The connection closed before the whole body came");
            }
            left -= n;
            return n;
        }
    }

    /** The body of an answer sent in chunks. */
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        private boolean next() throws IOException {
            if (done) {
                return false;
            }
            if (left == 0) {
                String size = readLine(in);
                if (size != null && size.isEmpty()) {
                    // the line end after the data of the chunk before
                    size = readLine(in);
                }
                if (size == null) {
                    throw new IOException("The connection closed in a chunked body");
                }
                int semicolon = size.indexOf(';');
                try {
                    left = Long.parseLong((semicolon < 0 ? size : size.substring(0, semicolon)).strip(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("A chunk of the answer has no size: " + size);
                }
                if (left == 0) {
                    // the trailers end with an empty line
                    String trailer;
                    while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                        // ignored
                    }
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!next()) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new IOException("The connection closed in a chunk");
            }
            left--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!next()) {
                return -1;
            }
            int n = in.read(buffer, offset, (int) Math.min(length, left));
            if (n < 0) {
                throw new IOException("The connection closed in a chunk");
            }
            left -= n;
            return n;
        }
    }
}
