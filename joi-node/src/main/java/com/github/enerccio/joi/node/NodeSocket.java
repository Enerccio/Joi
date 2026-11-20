package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AudioChunk;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.Done;
import com.github.enerccio.joi.protocol.http.TextDelta;
import com.github.enerccio.joi.protocol.node.Frame;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodeMessage;
import com.github.enerccio.joi.protocol.node.Say;
import com.github.enerccio.joi.protocol.node.ServerEvent;
import com.github.enerccio.joi.protocol.node.Stop;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolCancel;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolUse;
import com.github.enerccio.joi.protocol.node.TurnStart;
import com.github.enerccio.joi.protocol.node.Welcome;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The node's end of the node WebSocket ({@code asyncapi.yaml}): hello and the manifest, her turns (a {@code say} or one
 * the server starts with {@code turn}) told to a {@link Listener} as their frames come, tool calls answered by a
 * {@link #setTools tool handler}, the heartbeat, and connecting again when the connection is lost. Frames are read on the WebSocket's thread; a tool runs on a
 * virtual thread of its own.
 */
public class NodeSocket {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** What happens on the connection; every method runs on the WebSocket's thread. */
    public interface Listener {
        /** A turn began: a say of this node ({@code cause} {@code say}) or one the server started. */
        default void turnStarted(String turn, String cause) {
        }

        default void reasoning(String turn, String delta) {
        }

        default void text(String turn, String delta) {
        }

        default void toolUsed(String turn, ToolUse use) {
        }

        /** The text is whole and stored. */
        default void reply(String turn, ChatReply reply) {
        }

        default void audio(String turn, AudioChunk chunk) {
        }

        default void done(String turn, Done done) {
        }

        /** An error: of a turn ({@code ref} its id; {@code stopped} when it was stopped), or of a frame of the node. */
        default void failed(String ref, ApiError error) {
        }

        default void event(ServerEvent event) {
        }

        /** The connection was lost: it connects again until it is back ({@link #reconnected}) or over ({@link #closed}). */
        default void disconnected(int code, String reason) {
        }

        /** It is connected again (said {@code hello} with {@code resumed}); a turn that ran when it was lost is lost. */
        default void reconnected(Welcome welcome) {
        }

        /** The connection closed for good (by {@link #close}, or a close code after which it does not try again). */
        default void closed(int code, String reason) {
        }
    }

    /** The cause of a turn a say of the node started. */
    public static final String SAY = "say";

    /** The close codes after which the node does not connect again: unauthorized, version, replaced, not found, revoked. */
    static final Set<Integer> FINAL = Set.of(4001, 4002, 4003, 4004, 4009);
    /** The longest wait between two tries to connect again, in seconds. */
    static final long MAX_WAIT_SECONDS = 30;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Joi node heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong ids = new AtomicLong();
    private final Listener listener;
    private Function<ToolCall, ToolResult> tools = call -> new ToolResult(call.callId(), false,
            "This node has no tool " + call.tool(), "unknown_tool");
    private volatile WebSocket socket;
    /** Where frames go; the WebSocket by default, a test's own otherwise. */
    private Consumer<String> out = this::sendText;
    private boolean testing;

    private URI uri;
    private String authorization;
    private Hello hello;
    private volatile Manifest manifest;
    /** Each try to connect is a generation; what an older one's WebSocket says is ignored. */
    private final AtomicInteger generation = new AtomicInteger();
    private int ended = -1;
    private CompletableFuture<Welcome> welcome = new CompletableFuture<>();
    private ScheduledFuture<?> heartbeat;
    private volatile boolean connected;
    private volatile boolean closing;
    /** Over for good: closed, or a close code after which it does not try again. */
    private volatile boolean over;
    /** What the user said while it was not connected: sent once it is (welcomed, the manifest sent). */
    private final List<Map.Entry<String, Say>> queued = new ArrayList<>();
    private volatile long lastHeard;
    private int tries;

    public NodeSocket(Listener listener) {
        this.listener = listener;
    }

    /** Runs the node's tools: the result of a call (on a thread of its own; it may wait for the user). */
    public void setTools(Function<ToolCall, ToolResult> tools) {
        this.tools = tools;
    }

    /**
     * Connects, says hello and, once welcomed, sends the manifest. When the connection is lost later, it connects
     * again (waiting 1 s, 2 s, 4 s... up to {@link #MAX_WAIT_SECONDS}) with {@code hello.resumed} and the latest
     * manifest, until it is back or a close code says it is over ({@link #FINAL}).
     *
     * @param authorization the {@code Authorization} header, null for none
     * @return the welcome; it fails when the server closes the connection instead (it does not try again then)
     */
    public CompletableFuture<Welcome> connect(URI uri, String authorization, Hello hello, Manifest manifest) {
        this.uri = uri;
        this.authorization = authorization;
        this.hello = hello;
        this.manifest = manifest;
        return open(hello).whenComplete((w, error) -> {
            if (error != null) {
                over();
            }
        });
    }

    /** Whether the server welcomed this connection and it is still open. */
    public boolean connected() {
        return connected;
    }

    private CompletableFuture<Welcome> open(Hello hello) {
        int gen = generation.incrementAndGet();
        CompletableFuture<Welcome> welcomed = new CompletableFuture<>();
        synchronized (this) {
            welcome = welcomed;
        }
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        builder.buildAsync(uri, new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    String frame = text.toString();
                    text.setLength(0);
                    if (gen == generation.get()) {
                        receive(frame);
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                ended(gen, statusCode, reason);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                ended(gen, 1006, String.valueOf(error.getMessage()));
            }
        }).whenComplete((ws, error) -> {
            if (error != null) {
                welcomed.completeExceptionally(error);
                return;
            }
            if (closing || gen != generation.get()) {
                ws.abort();
                welcomed.completeExceptionally(new Closed(WebSocket.NORMAL_CLOSURE, "closed"));
                return;
            }
            socket = ws;
            lastHeard = System.currentTimeMillis();
            send(NodeMessage.HELLO, "hello", null, hello);
        });
        return welcomed.thenApply(w -> {
            send(NodeMessage.MANIFEST, null, null, manifest);
            long every = Math.max(1, w.heartbeatSeconds() / 2);
            long silent = Math.max(2, w.heartbeatSeconds() * 2L) * 1000;
            synchronized (this) {
                heartbeat = ticks.scheduleAtFixedRate(() -> beat(gen, silent), every, every, TimeUnit.SECONDS);
            }
            tries = 0;
            synchronized (queued) {
                for (Map.Entry<String, Say> say : queued) {
                    send(NodeMessage.SAY, say.getKey(), null, say.getValue());
                }
                queued.clear();
                connected = true;
            }
            return w;
        });
    }

    /** A ping, or the end of a connection the server stopped answering on. */
    private void beat(int gen, long silent) {
        if (System.currentTimeMillis() - lastHeard > silent) {
            WebSocket ws = socket;
            if (ws != null) {
                ws.abort();
            }
            ended(gen, 4008, "The server did not answer for " + silent / 1000 + " s");
            return;
        }
        send(NodeMessage.PING, "ping-" + ids.incrementAndGet(), null, null);
    }

    /**
     * The user said something: returns the id of the turn her answer will refer to. While it is not connected, it
     * waits and is sent once it is; it fails when the connection is over for good.
     */
    public String say(String text, boolean speak, boolean reasoning) {
        String turn = "t-" + ids.incrementAndGet();
        Say say = new Say(text, speak, reasoning);
        listener.turnStarted(turn, SAY);
        if (!testing) {
            synchronized (queued) {
                if (over) {
                    listener.failed(turn, new ApiError("disconnected", "Not connected to the server"));
                    return turn;
                }
                if (!connected) {
                    queued.add(Map.entry(turn, say));
                    return turn;
                }
            }
        }
        send(NodeMessage.SAY, turn, null, say);
        return turn;
    }

    /** Over for good: what waited to be said fails. */
    private void over() {
        List<Map.Entry<String, Say>> failed;
        synchronized (queued) {
            over = true;
            failed = List.copyOf(queued);
            queued.clear();
        }
        for (Map.Entry<String, Say> say : failed) {
            listener.failed(say.getKey(), new ApiError("disconnected", "Not connected to the server"));
        }
    }

    /** Stops her turn ({@code null} for the one running now): she was talked over. */
    public void stop(String turn) {
        send(NodeMessage.STOP, null, null, new Stop(turn));
    }

    /** Sends a newer manifest (its version higher than the last); it is the one sent when connecting again. */
    public void manifest(Manifest manifest) {
        this.manifest = manifest;
        send(NodeMessage.MANIFEST, null, null, manifest);
    }

    /** Closes the connection, for good. */
    public void close() {
        closing = true;
        over();
        synchronized (this) {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        ticks.shutdownNow();
    }

    /** The connection of that generation ended (once; an older one's end is ignored). */
    private void ended(int gen, int code, String reason) {
        boolean was;
        CompletableFuture<Welcome> welcomed;
        synchronized (this) {
            if (gen != generation.get() || ended == gen) {
                return;
            }
            ended = gen;
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
            was = connected;
            connected = false;
            socket = null;
            welcomed = welcome;
        }
        welcomed.completeExceptionally(new Closed(code, reason));
        if (!was) {
            // not welcomed yet: the try to connect failed, its caller goes on
            return;
        }
        if (closing || FINAL.contains(code)) {
            over();
            listener.closed(code, reason);
            return;
        }
        listener.disconnected(code, reason);
        reconnect();
    }

    /** One more try to connect, after a wait that doubles with each try. */
    private void reconnect() {
        if (closing) {
            return;
        }
        long wait = Math.min(MAX_WAIT_SECONDS, 1L << Math.min(tries, 5));
        tries++;
        try {
            ticks.schedule(() -> {
                if (closing) {
                    return;
                }
                Hello again = new Hello(hello.protocol(), hello.nodeId(), hello.name(), hello.kind(), hello.software(),
                        hello.joi(), hello.parts(), hello.token(), true);
                open(again).whenComplete((w, error) -> {
                    if (error == null) {
                        listener.reconnected(w);
                        return;
                    }
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    if (cause instanceof Closed closed && FINAL.contains(closed.code)) {
                        over();
                        listener.closed(closed.code, closed.reason);
                    } else {
                        reconnect();
                    }
                });
            }, wait, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closed meanwhile
        }
    }

    /** The server closed the connection (or it was lost). */
    static final class Closed extends IllegalStateException {
        final int code;
        final String reason;

        Closed(int code, String reason) {
            super("Closed " + code + ": " + reason);
            this.code = code;
            this.reason = reason;
        }
    }

    /** Sends a frame through {@link #out}. */
    void send(NodeMessage type, String id, String ref, Object data) {
        JsonElement json = data == null ? null : GSON.toJsonTree(data);
        out.accept(GSON.toJson(new Frame(type.type(), id, ref, json)));
    }

    private final Object sending = new Object();

    private void sendText(String text) {
        WebSocket ws = socket;
        if (ws != null) {
            synchronized (sending) {
                // one frame at a time: a WebSocket takes the next send once the last is done
                try {
                    ws.sendText(text, true).join();
                } catch (RuntimeException e) {
                    // the connection is closing: its end is told by the listener
                }
            }
        }
    }

    /** For tests: frames go here instead of a WebSocket. */
    void sendTo(Consumer<String> out) {
        this.out = out;
        this.testing = true;
    }

    /** A frame from the server. */
    void receive(String text) {
        lastHeard = System.currentTimeMillis();
        Frame frame;
        try {
            frame = GSON.fromJson(text, Frame.class);
        } catch (JsonParseException e) {
            return;
        }
        NodeMessage type = frame == null ? null : NodeMessage.of(frame.type());
        if (type == null) {
            // a type of a newer version: ignored
            return;
        }
        String ref = frame.ref();
        switch (type) {
            case WELCOME -> {
                CompletableFuture<Welcome> welcomed;
                synchronized (this) {
                    welcomed = welcome;
                }
                welcomed.complete(data(frame, Welcome.class));
            }
            case TURN -> {
                TurnStart start = data(frame, TurnStart.class);
                listener.turnStarted(start.turn(), start.cause());
            }
            case REASONING -> listener.reasoning(ref, data(frame, TextDelta.class).delta());
            case TEXT -> listener.text(ref, data(frame, TextDelta.class).delta());
            case TOOL -> listener.toolUsed(ref, data(frame, ToolUse.class));
            case REPLY -> listener.reply(ref, data(frame, ChatReply.class));
            case AUDIO -> listener.audio(ref, data(frame, AudioChunk.class));
            case DONE -> listener.done(ref, data(frame, Done.class));
            case ERROR -> listener.failed(ref, data(frame, ApiError.class));
            case EVENT -> listener.event(data(frame, ServerEvent.class));
            case TOOL_CALL -> call(data(frame, ToolCall.class));
            case TOOL_CANCEL -> {
                // the tools of this node finish quickly; a long one would stop here (data(frame, ToolCancel.class))
                data(frame, ToolCancel.class);
            }
            case PING -> send(NodeMessage.PONG, null, frame.id(), null);
            default -> {
                // pong
            }
        }
    }

    private void call(ToolCall call) {
        Thread.ofVirtual().name("Joi node tool " + call.tool()).start(() -> {
            ToolResult result;
            try {
                result = tools.apply(call);
            } catch (RuntimeException e) {
                result = new ToolResult(call.callId(), false, String.valueOf(e.getMessage()), "failed");
            }
            send(NodeMessage.TOOL_RESULT, null, null, result);
        });
    }

    private static <T> T data(Frame frame, Class<T> type) {
        return frame.data() == null ? null : GSON.fromJson(frame.data(), type);
    }
}
