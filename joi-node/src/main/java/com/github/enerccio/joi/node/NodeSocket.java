package com.github.enerccio.joi.node;

import com.github.enerccio.joi.node.local.UnixWebSocket;
import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.node.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
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
    private static final System.Logger LOG = System.getLogger(NodeSocket.class.getName());

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

        /** A note in her conversation (the user approved a task, say): not an exchange, not for the model. */
        default void log(com.github.enerccio.joi.protocol.http.ChatLog log) {
        }

        /**
         * A task of hers was made or changed its status (since 1.7). One that is {@code waiting} is for the node to ask the user
         * about: answer with {@link ServerClient#approveTask} or {@link ServerClient#denyTask}. The next call for the same id with
         * another status says it was answered, here or elsewhere: take the question back.
         */
        default void task(com.github.enerccio.joi.protocol.http.TaskInfo task) {
        }

        /** Her mood now and how it shows on her face (since 1.10): sent after the manifest and each time it changes. */
        default void mood(com.github.enerccio.joi.protocol.node.MoodState mood) {
        }

        /** She plays an animation of her own choice (her {@code play_animation}): see {@link AvatarDirector#gesture}. */
        default void animation(PlayAnimation animation) {
        }

        /** The server kept a part in the state asked for by {@link #part(String, String, String)} (the id is the frame's). */
        default void partKept(String id, PartKept kept) {
        }

        /** She did not allow a change asked for by {@link #part(String, String, String)} (the id is the frame's). */
        default void partRefused(String id, PartRefused refused) {
        }

        /** The connection was lost: it connects again until it is back ({@link #reconnected}) or over ({@link #closed}). */
        default void disconnected(int code, String reason) {
        }

        /** The next try to connect again is in this many seconds ({@link #reconnectNow} makes it now). */
        default void retrying(long waitSeconds) {
        }

        /** A try to connect again begins. */
        default void connecting() {
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
    /** The server's local socket when it is on this machine, else null (the network). */
    private volatile Path localSocket;
    /** Gives the {@code Authorization} header of a try to connect again (an access token that expired is made again), null to keep the first. */
    private volatile Callable<String> authorizer;
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
    private volatile long lastHeard;
    private int tries;
    /** What the node knows of the connection it lost, said in the next hello: when this run of the server started, when and how it was lost. */
    private volatile String serverStartedAt;
    private volatile String lostAt;
    private volatile Integer lostCode;
    /** The try to connect again that waits, and whether one runs now. */
    private ScheduledFuture<?> retry;
    private boolean attempting;

    public NodeSocket(Listener listener) {
        this.listener = listener;
    }

    /** Connects over the Unix domain socket of a server on this machine instead of the network (the URI's host and port are not used). */
    public void localSocket(Path socket) {
        this.localSocket = socket;
    }

    /**
     * Where the {@code Authorization} header comes from when the node connects again: the connection may be lost for longer than an
     * access token lives, and the header the first connection had is then no good (the server closes with {@code unauthorized}, which
     * ends the node for good). Not used for the first connection, which has the header it is given.
     */
    public void authorizer(Callable<String> authorizer) {
        this.authorizer = authorizer;
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
        String header = authorization;
        if (gen > 1 && authorizer != null) {
            try {
                header = authorizer.call();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "The credentials for connecting again could not be made: " + e, e);
            }
        }
        String authorization = header;
        WebSocket.Listener events = new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    String frame = text.toString();
                    text.setLength(0);
                    if (gen == generation.get()) {
                        // a frame that fails (a listener's error, a frame without its data) must not stop the reading
                        try {
                            receive(frame);
                        } catch (RuntimeException e) {
                            LOG.log(System.Logger.Level.WARNING, "A frame of the server could not be handled: " + e, e);
                        }
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
        };
        CompletableFuture<WebSocket> opened;
        Path unix = localSocket;
        if (unix != null) {
            opened = UnixWebSocket.connectAsync(unix, uri, authorization == null ? Map.of() : Map.of("Authorization", authorization), events);
        } else {
            WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            opened = builder.buildAsync(uri, events);
        }
        opened.whenComplete((ws, error) -> {
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
            connected = true;
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
     * The user said something: returns the id of the turn her answer will refer to. While it is not connected nothing is
     * sent, and nothing is kept to be sent later: it fails ({@link Listener#failed}, {@code disconnected}) and the user says
     * it again when she is back.
     */
    public String say(String text, boolean speak, boolean reasoning) {
        return say(text, speak, reasoning, false);
    }

    /**
     * As {@link #say(String, boolean, boolean)}; {@code withEvents} takes along what the server holds to tell her, the
     * changes of her parts sent just before: one turn about the words and those, whose id is the one returned.
     */
    public String say(String text, boolean speak, boolean reasoning, boolean withEvents) {
        return say(text, speak, reasoning, withEvents, List.of());
    }

    /**
     * As {@link #say(String, boolean, boolean, boolean)} with images to look at (since 1.11): the ids {@link ServerClient#uploadImage}
     * gave, at most 4; the text may be empty then.
     */
    public String say(String text, boolean speak, boolean reasoning, boolean withEvents, List<String> images) {
        String turn = "t-" + ids.incrementAndGet();
        Say say = new Say(text, speak, reasoning, withEvents ? true : null, images == null || images.isEmpty() ? null : images);
        if (!testing && !connected()) {
            // offline nothing is said: it is not kept to be sent later, the user says it again once she is there
            listener.failed(turn, new ApiError("disconnected", "Not connected to the server: nothing was sent"));
            return turn;
        }
        listener.turnStarted(turn, SAY);
        send(NodeMessage.SAY, turn, null, say);
        return turn;
    }

    /** Over for good. */
    private void over() {
        over = true;
    }

    /** Stops her turn ({@code null} for the one running now): she was talked over. */
    public void stop(String turn) {
        if (!connected()) {
            return;
        }
        send(NodeMessage.STOP, null, null, new Stop(turn));
    }

    /**
     * Tells the server she is shown on a Looking Glass now, or no longer, as the user chose (since 1.4): she reacts to
     * it in a turn the server starts. Not sent (nor kept) while the node is not connected.
     */
    public void display(boolean lookingGlass) {
        if (connected()) {
            send(NodeMessage.DISPLAY, null, null, new Display(lookingGlass));
        }
    }

    /**
     * Tells the server a part of her body is to be put in another state (since 1.5): it keeps it on the body and tells
     * her. The server answers {@link Listener#partKept} once it is kept, {@link Listener#partRefused} when she did not
     * allow it, or {@link Listener#failed} (all with the id). Not sent (nor kept) while the node is not connected.
     *
     * @return whether it was sent
     */
    public boolean part(String id, String part, String state) {
        if (!connected()) {
            return false;
        }
        send(NodeMessage.PART, id, null, new PartSwitch(part, state));
        return true;
    }

    /** Tells the server where the approval of a call the node asked the user about is (the result still comes as the call's result). */
    public void approval(String callId, com.github.enerccio.joi.protocol.node.ApprovalState state, String scope, String via) {
        send(NodeMessage.APPROVAL, null, null, new com.github.enerccio.joi.protocol.node.Approval(callId, state, scope, via));
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
        lostAt = java.time.Instant.now().toString();
        lostCode = code;
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
            synchronized (this) {
                retry = ticks.schedule(this::attempt, wait, TimeUnit.SECONDS);
            }
            listener.retrying(wait);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closed meanwhile
        }
    }

    /**
     * Tries to connect again now, without waiting for the next try (the user asked): nothing when it is connected, over,
     * or a try is running already. The wait of the tries after it starts from 1 s again.
     */
    public void reconnectNow() {
        synchronized (this) {
            if (connected || closing || over || attempting || retry == null) {
                return;
            }
            retry.cancel(false);
            retry = null;
            tries = 0;
        }
        try {
            ticks.execute(this::attempt);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closed meanwhile
        }
    }

    /** A try to connect again: {@code hello} with what the node knows of the connection it lost. */
    private void attempt() {
        if (closing) {
            return;
        }
        synchronized (this) {
            if (attempting) {
                return;
            }
            attempting = true;
            retry = null;
        }
        listener.connecting();
        Hello again = new Hello(hello.protocol(), hello.nodeId(), hello.name(), hello.kind(), hello.software(),
                hello.joi(), hello.parts(), hello.token(), true, new Resume(serverStartedAt, lostAt, lostCode));
        open(again).whenComplete((w, error) -> {
            synchronized (this) {
                attempting = false;
            }
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
                Welcome welcomedBy = data(frame, Welcome.class);
                serverStartedAt = welcomedBy.serverStartedAt();
                welcomed.complete(welcomedBy);
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
            case PART_KEPT -> listener.partKept(ref, data(frame, PartKept.class));
            case PART_REFUSED -> listener.partRefused(ref, data(frame, PartRefused.class));
            case EVENT -> listener.event(data(frame, ServerEvent.class));
            case ANIMATION -> listener.animation(data(frame, PlayAnimation.class));
            case MOOD -> listener.mood(data(frame, com.github.enerccio.joi.protocol.node.MoodState.class));
            case LOG -> listener.log(data(frame, com.github.enerccio.joi.protocol.http.ChatLog.class));
            case TASK -> listener.task(data(frame, com.github.enerccio.joi.protocol.http.TaskInfo.class));
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
