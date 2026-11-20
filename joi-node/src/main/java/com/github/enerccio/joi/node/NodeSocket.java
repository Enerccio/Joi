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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The node's end of the node WebSocket ({@code asyncapi.yaml}): hello and the manifest, her turns (a {@code say} or one
 * the server starts with {@code turn}) told to a {@link Listener} as their frames come, tool calls answered by a
 * {@link #setTools tool handler}, and the heartbeat. Frames are read on the WebSocket's thread; a tool runs on a
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

        /** The connection closed (by the server, the network or {@link #close}). */
        default void closed(int code, String reason) {
        }
    }

    /** The cause of a turn a say of the node started. */
    public static final String SAY = "say";

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
    private final CompletableFuture<Welcome> welcome = new CompletableFuture<>();
    private ScheduledFuture<?> heartbeat;
    /** Where frames go; the WebSocket by default, a test's own otherwise. */
    private Consumer<String> out = this::sendText;

    public NodeSocket(Listener listener) {
        this.listener = listener;
    }

    /** Runs the node's tools: the result of a call (on a thread of its own; it may wait for the user). */
    public void setTools(Function<ToolCall, ToolResult> tools) {
        this.tools = tools;
    }

    /**
     * Connects, says hello and, once welcomed, sends the manifest.
     *
     * @param authorization the {@code Authorization} header, null for none
     * @return the welcome; it fails when the server closes the connection instead
     */
    public CompletableFuture<Welcome> connect(URI uri, String authorization, Hello hello, Manifest manifest) {
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
                    receive(frame);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                ended(statusCode, reason);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                ended(1006, String.valueOf(error.getMessage()));
            }
        }).whenComplete((ws, error) -> {
            if (error != null) {
                welcome.completeExceptionally(error);
                return;
            }
            socket = ws;
            send(NodeMessage.HELLO, "hello", null, hello);
        });
        return welcome.thenApply(w -> {
            send(NodeMessage.MANIFEST, null, null, manifest);
            long every = Math.max(1, w.heartbeatSeconds() / 2);
            heartbeat = ticks.scheduleAtFixedRate(() -> send(NodeMessage.PING, "ping-" + ids.incrementAndGet(), null, null),
                    every, every, TimeUnit.SECONDS);
            return w;
        });
    }

    /** The user said something: returns the id of the turn her answer will refer to. */
    public String say(String text, boolean speak, boolean reasoning) {
        String turn = "t-" + ids.incrementAndGet();
        send(NodeMessage.SAY, turn, null, new Say(text, speak, reasoning));
        listener.turnStarted(turn, SAY);
        return turn;
    }

    /** Stops her turn ({@code null} for the one running now): she was talked over. */
    public void stop(String turn) {
        send(NodeMessage.STOP, null, null, new Stop(turn));
    }

    /** Sends a newer manifest (its version higher than the last). */
    public void manifest(Manifest manifest) {
        send(NodeMessage.MANIFEST, null, null, manifest);
    }

    /** Closes the connection. */
    public void close() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        ticks.shutdownNow();
    }

    private void ended(int code, String reason) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        welcome.completeExceptionally(new IllegalStateException("Closed " + code + ": " + reason));
        listener.closed(code, reason);
    }

    /** Sends a frame through {@link #out}. */
    void send(NodeMessage type, String id, String ref, Object data) {
        JsonElement json = data == null ? null : GSON.toJsonTree(data);
        out.accept(GSON.toJson(new Frame(type.type(), id, ref, json)));
    }

    private void sendText(String text) {
        WebSocket ws = socket;
        if (ws != null) {
            synchronized (this) {
                // one frame at a time: a WebSocket takes the next send once the last is done
                ws.sendText(text, true).join();
            }
        }
    }

    /** For tests: frames go here instead of a WebSocket. */
    void sendTo(Consumer<String> out) {
        this.out = out;
    }

    /** A frame from the server. */
    void receive(String text) {
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
            case WELCOME -> welcome.complete(data(frame, Welcome.class));
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
