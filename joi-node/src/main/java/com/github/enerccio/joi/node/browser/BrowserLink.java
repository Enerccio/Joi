package com.github.enerccio.joi.node.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The node's end of the browser companion (DESIGN.md → Node → Browser companion): a Unix socket in the node's home
 * ({@code browser.sock}) that the native messaging host ({@link NativeHost}) connects to, one JSON message a line. The extension says
 * {@code hello} through it; then the node sends {@code request}s (an id, an op, its arguments) and the extension answers each with a
 * {@code result}. One extension at a time: a newer connection replaces the older one.
 */
public final class BrowserLink implements AutoCloseable {

    /** The socket's name in the node's home. */
    public static final String SOCKET = "browser.sock";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Told when the extension comes or goes (its tools come and go with it). */
    public interface Listener {
        void changed(boolean connected);
    }

    /** The extension answered a request with an error. */
    public static final class BrowserException extends IOException {
        public BrowserException(String message) {
            super(message);
        }
    }

    private final Path socket;
    private final AtomicLong ids = new AtomicLong();
    private volatile Listener listener = connected -> {
    };
    private ServerSocketChannel server;
    private volatile Connection current;
    private volatile boolean closed;

    public BrowserLink(Path nodeHome) {
        this.socket = nodeHome.toAbsolutePath().resolve(SOCKET);
    }

    public Path socket() {
        return socket;
    }

    public void listener(Listener listener) {
        this.listener = listener == null ? connected -> {
        } : listener;
    }

    /** Opens the socket (a stale one from an earlier run is removed) and accepts the host's connections. */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        Files.createDirectories(socket.getParent());
        Files.deleteIfExists(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        try {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the home's own permissions keep it
        }
        ServerSocketChannel accepting = server;
        Thread.ofVirtual().name("Joi browser link").start(() -> {
            while (!closed) {
                try {
                    SocketChannel channel = accepting.accept();
                    Connection old = current;
                    Connection connection = new Connection(channel);
                    current = connection;
                    if (old != null) {
                        old.close();
                    }
                    Thread.ofVirtual().name("Joi browser link reader").start(connection::read);
                } catch (IOException e) {
                    if (!closed) {
                        // the socket is broken: no more extensions this run
                        return;
                    }
                }
            }
        });
    }

    /** The extension is there and said hello. */
    public boolean connected() {
        Connection c = current;
        return c != null && c.hello != null;
    }

    /** What the extension said of itself ({@code browser}, {@code version}), null when it is not connected. */
    public JsonObject hello() {
        Connection c = current;
        return c == null ? null : c.hello;
    }

    /**
     * Asks the extension and waits for its answer.
     *
     * @return the result's {@code data} (an empty object for none)
     * @throws BrowserException the extension answered with an error
     * @throws IOException      no extension, or no answer in time
     */
    public JsonObject request(String op, JsonObject args, long timeoutMs) throws IOException, InterruptedException {
        Connection c = current;
        if (c == null || c.hello == null) {
            throw new IOException("Firefox is not connected to this node.");
        }
        long id = ids.incrementAndGet();
        CompletableFuture<JsonObject> answer = new CompletableFuture<>();
        c.pending.put(id, answer);
        JsonObject message = new JsonObject();
        message.addProperty("type", "request");
        message.addProperty("id", id);
        message.addProperty("op", op);
        message.add("args", args == null ? new JsonObject() : args);
        try {
            c.send(message);
            return answer.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Firefox did not answer in time.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(String.valueOf(cause == null ? e.getMessage() : cause.getMessage()));
        } finally {
            c.pending.remove(id);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        Connection c = current;
        current = null;
        if (c != null) {
            c.close();
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                // closing anyway
            }
            server = null;
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException e) {
            // a stale socket is removed at the next start
        }
    }

    private final class Connection {
        private final SocketChannel channel;
        private final OutputStream out;
        private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        private volatile JsonObject hello;

        Connection(SocketChannel channel) {
            this.channel = channel;
            this.out = Channels.newOutputStream(channel);
        }

        void read() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject message;
                    try {
                        JsonElement parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) {
                            continue;
                        }
                        message = parsed.getAsJsonObject();
                    } catch (JsonParseException e) {
                        continue;
                    }
                    received(message);
                }
            } catch (IOException e) {
                // the host went away
            } finally {
                gone();
            }
        }

        private void received(JsonObject message) {
            String type = message.has("type") && message.get("type").isJsonPrimitive() ? message.get("type").getAsString() : "";
            switch (type) {
                case "hello" -> {
                    boolean first = hello == null;
                    hello = message;
                    if (first && current == this) {
                        listener.changed(true);
                    }
                }
                case "result" -> {
                    if (!message.has("id") || !message.get("id").isJsonPrimitive()) {
                        return;
                    }
                    CompletableFuture<JsonObject> answer = pending.get(message.get("id").getAsLong());
                    if (answer == null) {
                        return;
                    }
                    boolean ok = message.has("ok") && message.get("ok").isJsonPrimitive() && message.get("ok").getAsBoolean();
                    if (ok) {
                        JsonElement data = message.get("data");
                        answer.complete(data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject());
                    } else {
                        JsonElement error = message.get("error");
                        answer.completeExceptionally(new BrowserException(error != null && error.isJsonPrimitive() ? error.getAsString() : "It failed in Firefox."));
                    }
                }
                default -> {
                    // nothing else is expected from the extension
                }
            }
        }

        synchronized void send(JsonObject message) throws IOException {
            out.write((GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void gone() {
            boolean wasConnected = hello != null;
            pending.values().forEach(p -> p.completeExceptionally(new IOException("Firefox went away.")));
            pending.clear();
            if (current == this) {
                current = null;
                if (wasConnected) {
                    listener.changed(false);
                }
            }
        }

        void close() {
            try {
                channel.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
    }
}
