package com.github.enerccio.joi.domain.node;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.node.Frame;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodeMessage;
import com.github.enerccio.joi.protocol.node.NodePart;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolCancel;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.domain.service.function.FunctionContinuation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * One connection of a node (see {@code DESIGN.md} → The node protocol): who it is ({@link Hello}), for which assistant,
 * as which user, what it offers ({@link Manifest}), the turn it started that runs now, and the tool calls it owes an
 * answer. The frames themselves are handled by {@code NodeServiceImpl}; this holds the state and sends.
 * <p>
 * Its user's request scope ({@link #attributes()}) lives as long as the connection: its turns run in it.
 */
public class NodeSession {

    /** Frames as the protocol writes them: nulls left out. */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String id = UUID.randomUUID().toString();
    private final NodeTransport transport;
    /** The {@code Authorization} header of the upgrade, null for none. */
    private final String authorization;
    private final long openedAt = System.currentTimeMillis();
    private volatile long lastSeen = openedAt;

    private volatile Principal principal;
    private volatile ApiRequestAttributes attributes;
    private volatile Long joiId;
    private volatile String joiUuid;
    private volatile Hello hello;
    private volatile Manifest manifest;
    private volatile boolean closed;

    /** The turn this node started that runs now: the id of its say (or of the server's turn) and its result. */
    private volatile String turnId;
    private volatile CompletableFuture<?> turn;

    /**
     * Something that happened to her that she is not told yet.
     *
     * @param text    what she is told
     * @param request the number of the request of the user she is to decide on, null for an event
     */
    public record Pending(String text, Integer request) {
    }

    /**
     * A change of a part the user asked for, that she decides on (her personality lets her): the body stays as it is
     * until she allows it.
     *
     * @param number  what she calls it (the number the tool takes)
     * @param frameId the id of the node's {@code part} frame, null when it had none (nothing is answered then)
     */
    public record PartRequest(int number, String frameId, String part, String state) {
    }

    /** What she is not told yet, in order, and the timer that will tell her. */
    private final List<Pending> pendingEvents = new ArrayList<>();
    private ScheduledFuture<?> gather;
    /** The requests she was not answered yet, by number; the turn she was told them in (null: not told yet). */
    private final Map<Integer, PartRequest> requests = new LinkedHashMap<>();
    private final Map<Integer, String> toldIn = new HashMap<>();
    private int requestCounter;

    private final Map<String, PendingCall> calls = new ConcurrentHashMap<>();

    /**
     * A call of one of its tools waiting for the result.
     *
     * @param tool         the tool, as the manifest names it
     * @param turn         the turn of this node that called it, null for another
     * @param continuation where the result goes
     * @param timeoutMs    how long after the last frame about it the server waits
     */
    public record PendingCall(String tool, String turn, FunctionContinuation<Object> continuation, long timeoutMs, long[] touched,
                              boolean[] asked) {

        /** A frame about the call came: its timeout starts again. */
        public void touch() {
            touched[0] = System.currentTimeMillis();
        }

        public boolean expired(long now) {
            return !asked[0] && now - touched[0] > timeoutMs;
        }
    }

    public NodeSession(NodeTransport transport, String authorization) {
        this.transport = transport;
        this.authorization = authorization;
    }

    public String id() {
        return id;
    }

    public String authorization() {
        return authorization;
    }

    public long openedAt() {
        return openedAt;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void seen() {
        lastSeen = System.currentTimeMillis();
    }

    public Principal principal() {
        return principal;
    }

    public ApiRequestAttributes attributes() {
        return attributes;
    }

    public Long joiId() {
        return joiId;
    }

    public String joiUuid() {
        return joiUuid;
    }

    public Hello hello() {
        return hello;
    }

    /** Whether it said hello and was welcomed. */
    public boolean welcomed() {
        return hello != null;
    }

    /** The node's short name ({@code desk}), null before hello. */
    public String name() {
        return hello == null ? null : hello.name();
    }

    /** Whether it plays her speech. */
    public boolean speaks() {
        return hello != null && hello.parts() != null && hello.parts().contains(NodePart.AUDIO_OUT);
    }

    public void welcome(Principal principal, ApiRequestAttributes attributes, Joi joi, Hello hello) {
        this.principal = principal;
        this.attributes = attributes;
        this.joiId = joi.getId();
        this.joiUuid = joi.getUuid();
        this.hello = hello;
    }

    public Manifest manifest() {
        return manifest;
    }

    /** Takes the manifest unless an older one; returns whether it was the first. */
    public synchronized boolean offer(Manifest manifest) {
        boolean first = this.manifest == null;
        if (first || manifest.version() >= this.manifest.version()) {
            this.manifest = manifest;
        }
        return first;
    }

    /** The tools it offers now. */
    public List<ToolDefinition> tools() {
        Manifest current = manifest;
        return current == null || current.tools() == null ? List.of() : current.tools();
    }

    public String turnId() {
        return turnId;
    }

    public CompletableFuture<?> turn() {
        return turn;
    }

    public synchronized void turnStarted(String id, CompletableFuture<?> turn) {
        this.turnId = id;
        this.turn = turn;
    }

    public synchronized void turnEnded(String id) {
        if (id.equals(turnId)) {
            turnId = null;
            turn = null;
        }
    }

    /**
     * Adds an event she is to be told about to the ones waiting; the timer that tells her is replaced by {@code next}
     * (the earlier one is cancelled), so what comes in a burst is told together.
     */
    public synchronized void pend(Pending item, java.util.function.Supplier<ScheduledFuture<?>> next) {
        pendingEvents.add(item);
        if (gather != null) {
            gather.cancel(false);
        }
        gather = next.get();
    }

    /**
     * Takes the events waiting, none while a turn of hers runs (they wait for it: {@link #turnEnded} is followed by
     * another try), or when the connection is closed.
     */
    public synchronized List<Pending> takePending() {
        if (turn != null || closed || pendingEvents.isEmpty()) {
            return List.of();
        }
        List<Pending> taken = List.copyOf(pendingEvents);
        pendingEvents.clear();
        gather = null;
        return taken;
    }

    /**
     * Takes the events waiting even while a turn of hers runs (a turn asked for with them follows that one): the user's
     * words take them along. None when the connection is closed.
     */
    public synchronized List<Pending> takeAllPending() {
        if (closed || pendingEvents.isEmpty()) {
            return List.of();
        }
        List<Pending> taken = List.copyOf(pendingEvents);
        pendingEvents.clear();
        if (gather != null) {
            gather.cancel(false);
        }
        gather = null;
        return taken;
    }

    /** Keeps a request of the user until she answers it; returns it with its number. */
    public synchronized PartRequest request(String frameId, String part, String state) {
        PartRequest request = new PartRequest(++requestCounter, frameId, part, state);
        requests.put(request.number(), request);
        return request;
    }

    /** The requests were told to her in the turn: from now on she may answer them. */
    public synchronized void told(List<Integer> numbers, String turn) {
        numbers.forEach(number -> toldIn.put(number, turn));
    }

    /** Takes the request she answers (told to her and not answered yet); null for none. */
    public synchronized PartRequest take(int number) {
        return toldIn.containsKey(number) ? requests.remove(number) : null;
    }

    /** Takes what she left unanswered of what the turn told her. */
    public synchronized List<PartRequest> takeUndecided(String turn) {
        List<PartRequest> left = new ArrayList<>();
        for (Map.Entry<Integer, String> told : List.copyOf(toldIn.entrySet())) {
            if (turn.equals(told.getValue())) {
                toldIn.remove(told.getKey());
                PartRequest request = requests.remove(told.getKey());
                if (request != null) {
                    left.add(request);
                }
            }
        }
        return left;
    }

    /** Whether she has requests to answer now (told to her, not answered yet). */
    public synchronized boolean hasOpenRequests() {
        return requests.keySet().stream().anyMatch(toldIn::containsKey);
    }

    /** Whether events wait to be told to her. */
    public synchronized boolean hasPending() {
        return !pendingEvents.isEmpty();
    }

    public Map<String, PendingCall> calls() {
        return calls;
    }

    /** Asks the node to run one of its tools; the result goes to the continuation. */
    public void call(String callId, String tool, JsonObject arguments, String turn, int timeoutSeconds,
                     FunctionContinuation<Object> continuation) {
        long now = System.currentTimeMillis();
        calls.put(callId, new PendingCall(tool, turn, continuation, timeoutSeconds * 1000L, new long[]{now}, new boolean[]{false}));
        send(NodeMessage.TOOL_CALL, null, null, new ToolCall(callId, tool, arguments, turn, timeoutSeconds));
    }

    /** The server no longer wants the result: the call fails with the reason and the node is told. */
    public void cancel(String callId, String reason) {
        PendingCall call = calls.remove(callId);
        if (call != null) {
            call.continuation().fail(reason);
            send(NodeMessage.TOOL_CANCEL, null, null, new ToolCancel(callId, reason));
        }
    }

    public boolean closed() {
        return closed;
    }

    public synchronized void markClosed() {
        closed = true;
        pendingEvents.clear();
        requests.clear();
        toldIn.clear();
        if (gather != null) {
            gather.cancel(false);
            gather = null;
        }
    }

    /** Sends a frame; data null for none. */
    public void send(NodeMessage type, String id, String ref, Object data) {
        if (closed) {
            return;
        }
        JsonElement json = data == null ? null : GSON.toJsonTree(data);
        transport.send(GSON.toJson(new Frame(type.type(), id, ref, json)));
    }

    /** An {@code error} frame (the connection stays). */
    public void error(String ref, String code, String message) {
        send(NodeMessage.ERROR, null, ref, new ApiError(code, message));
    }

    /** Closes the connection with a close code of the protocol. */
    public void close(int code, String reason) {
        if (!closed) {
            closed = true;
            transport.close(code, reason);
        }
    }
}
