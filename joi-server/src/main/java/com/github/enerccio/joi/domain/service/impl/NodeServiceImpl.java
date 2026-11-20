package com.github.enerccio.joi.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.api.JoiApi;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.ChatService.NoModelException;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.node.NodeSession.PendingCall;
import com.github.enerccio.joi.domain.node.NodeTransport;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.NodeEventTemplateData;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.AudioChunk;
import com.github.enerccio.joi.protocol.http.Done;
import com.github.enerccio.joi.protocol.http.JoiSummary;
import com.github.enerccio.joi.protocol.http.TextDelta;
import com.github.enerccio.joi.protocol.node.Approval;
import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Display;
import com.github.enerccio.joi.protocol.node.Frame;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodeMessage;
import com.github.enerccio.joi.protocol.node.Say;
import com.github.enerccio.joi.protocol.node.ServerEvent;
import com.github.enerccio.joi.protocol.node.Stop;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolProgress;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.github.enerccio.joi.protocol.node.ToolUse;
import com.github.enerccio.joi.protocol.node.TurnStart;
import com.github.enerccio.joi.protocol.node.Welcome;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * See {@link NodeService}. A node's tools are offered to every turn of its assistant by a listener of
 * {@link TurnEvents#COLLECT_TOOLS}; a call goes to the node as {@code tool_call} and the turn waits (nothing blocks a
 * thread) for its {@code tool_result}. Each connection keeps its user's request scope, which its turns run in.
 */
public class NodeServiceImpl implements NodeService, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NodeServiceImpl.class);

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern NODE_NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    /** The longest function name model APIs take. */
    private static final int MAX_FUNCTION_NAME = 64;

    /** The causes of a turn the server starts. */
    public static final String FIRST_BOOT = "first_boot";
    public static final String CONNECTED = "connected";
    /** The causes of her turns as the user moves her onto a Looking Glass and back ({@code display}). */
    public static final String LOOKING_GLASS_ENTERED = "looking_glass_entered";
    public static final String LOOKING_GLASS_EXITED = "looking_glass_exited";

    @Autowired
    private JoiApi joiApi;

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private PersonaService personaService;

    private final ObjectMapper jackson = new ObjectMapper();
    /** Every open connection, welcomed or not. */
    private final Set<NodeSession> open = ConcurrentHashMap.newKeySet();
    /** The welcomed ones by "user/assistant/node id": a node connecting again replaces its old connection. */
    private final Map<String, NodeSession> registered = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticks;
    private Registration tools;
    /** Whether the server greets a node that connected (off in tests that drive the turns themselves). */
    private volatile boolean greeting = true;

    @Override
    public void afterPropertiesSet() {
        ticks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Joi nodes");
            thread.setDaemon(true);
            return thread;
        });
        ticks.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        tools = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            List<FunctionDefinition> functions = new ArrayList<>();
            for (NodeSession session : sessions(event.getJoi().getId())) {
                for (ToolDefinition tool : session.tools()) {
                    FunctionDefinition function = function(session, tool);
                    if (function != null) {
                        functions.add(function);
                    }
                }
            }
            if (!functions.isEmpty()) {
                event.setFunctions(event.getFunctions().and(FunctionSet.bind(functions, this)));
            }
            chain.next();
        });
    }

    @Override
    public void destroy() {
        if (tools != null) {
            tools.unregister();
        }
        if (ticks != null) {
            ticks.shutdownNow();
        }
        for (NodeSession session : List.copyOf(open)) {
            session.close(1001, "The server is going down");
            closed(session);
        }
    }

    /** Whether a connected node is greeted (a turn of hers); tests turn it off. */
    public void setGreeting(boolean greeting) {
        this.greeting = greeting;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // connections
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public NodeSession open(NodeTransport transport, String authorization) {
        NodeSession session = new NodeSession(transport, StringUtils.defaultIfBlank(authorization, null));
        open.add(session);
        return session;
    }

    @Override
    public void closed(NodeSession session) {
        if (!open.remove(session)) {
            return;
        }
        session.close(1000, "Closed");
        if (session.welcomed()) {
            registered.remove(key(session), session);
        }
        for (String callId : List.copyOf(session.calls().keySet())) {
            PendingCall call = session.calls().remove(callId);
            if (call != null) {
                call.continuation().fail("The node " + session.name() + " disconnected");
            }
        }
        CompletableFuture<?> turn = session.turn();
        ApiRequestAttributes attributes = session.attributes();
        if (turn != null) {
            turn.cancel(true);
            // the turn cleans up in the connection's request scope: closed after it
            turn.whenComplete((r, e) -> closeQuietly(attributes));
        } else {
            closeQuietly(attributes);
        }
        log.info("Node {} of {} disconnected", session.name(), session.joiUuid());
    }

    private static void closeQuietly(ApiRequestAttributes attributes) {
        if (attributes != null) {
            attributes.close();
        }
    }

    @Override
    public List<NodeSession> sessions(Joi joi) {
        return sessions(joi.getId());
    }

    private List<NodeSession> sessions(Long joiId) {
        return registered.values().stream().filter(s -> joiId.equals(s.joiId()) && !s.closed()).toList();
    }

    @Override
    public void notify(Joi joi, String event) {
        for (NodeSession session : sessions(joi)) {
            session.send(NodeMessage.EVENT, null, null, new ServerEvent(event, Instant.now().toString()));
        }
    }

    private static String key(NodeSession session) {
        return session.principal().user().getId() + "/" + session.joiId() + "/" + session.hello().nodeId();
    }

    /** Closes the silent ones and fails the calls that waited too long. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (NodeSession session : List.copyOf(open)) {
                if (!session.welcomed() && now - session.openedAt() > HELLO_SECONDS * 1000L) {
                    session.close(4008, "No hello within " + HELLO_SECONDS + " s");
                    closed(session);
                } else if (session.welcomed() && now - session.lastSeen() > HEARTBEAT_SECONDS * 2000L) {
                    session.close(4008, "Nothing for " + HEARTBEAT_SECONDS * 2 + " s");
                    closed(session);
                } else {
                    for (Map.Entry<String, PendingCall> call : List.copyOf(session.calls().entrySet())) {
                        if (call.getValue().continuation().isCancelled()) {
                            // its turn was stopped (any turn of hers, also one of the HTTP API)
                            session.cancel(call.getKey(), "The turn was stopped");
                        } else if (call.getValue().expired(now)) {
                            session.cancel(call.getKey(), "The node " + session.name() + " did not answer in time");
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("The heartbeat of the nodes failed", e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // frames
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public void receive(NodeSession session, String text) {
        if (session.closed()) {
            return;
        }
        session.seen();
        Frame frame;
        try {
            frame = NodeSession.GSON.fromJson(text, Frame.class);
        } catch (JsonParseException e) {
            session.error(null, "bad_message", "Not a frame: " + e.getMessage());
            return;
        }
        if (frame == null || StringUtils.isBlank(frame.type())) {
            session.error(null, "bad_message", "A frame needs a type");
            return;
        }
        NodeMessage type = NodeMessage.of(frame.type());
        if (type == null) {
            // a type of a newer version: ignored
            return;
        }
        if (type.direction() == NodeMessage.Direction.TO_NODE) {
            session.error(frame.id(), "bad_message", "A node does not send " + frame.type());
            return;
        }
        if (!session.welcomed() && type != NodeMessage.HELLO && type != NodeMessage.PING && type != NodeMessage.PONG) {
            session.error(frame.id(), "bad_message", "Say hello first");
            return;
        }
        try {
            switch (type) {
                case HELLO -> hello(session, frame);
                case MANIFEST -> manifest(session, frame);
                case SAY -> say(session, frame);
                case STOP -> stop(session, frame);
                case DISPLAY -> display(session, frame);
                case TOOL_PROGRESS -> progress(session, frame);
                case TOOL_RESULT -> result(session, frame);
                case APPROVAL -> approval(session, frame);
                case PING -> session.send(NodeMessage.PONG, null, frame.id(), null);
                default -> {
                    // pong: the heartbeat was counted above
                }
            }
        } catch (JsonParseException | IllegalArgumentException e) {
            session.error(frame.id(), "bad_message", StringUtils.defaultIfBlank(e.getMessage(), "Bad message"));
        } catch (Exception e) {
            log.error("Node {}: the {} failed", session.name(), frame.type(), e);
            session.error(frame.id(), "internal", "Internal error");
        }
    }

    private static <T> T data(Frame frame, Class<T> type) {
        if (frame.data() == null || !frame.data().isJsonObject()) {
            throw new IllegalArgumentException("The " + frame.type() + " has no data");
        }
        T data = NodeSession.GSON.fromJson(frame.data(), type);
        if (data == null) {
            throw new IllegalArgumentException("The " + frame.type() + " has no data");
        }
        return data;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private void hello(NodeSession session, Frame frame) throws Exception {
        require(!session.welcomed(), "Hello was said already");
        Hello hello = data(frame, Hello.class);
        require(hello.protocol() != null && hello.protocol().matches("\\d+\\.\\d+"), "hello.protocol is major.minor");
        if (!hello.protocol().startsWith("1.")) {
            session.close(4002, "Version " + hello.protocol() + " is not served: this server speaks " + JoiProtocol.NODE_VERSION);
            closed(session);
            return;
        }
        require(hello.nodeId() != null && ID.matcher(hello.nodeId()).matches(), "hello.nodeId is missing or not an id");
        require(hello.name() != null && NODE_NAME.matcher(hello.name()).matches(),
                "hello.name is a short lowercase name (desk, kitchen-pi)");
        require(StringUtils.isNotBlank(hello.joi()), "hello.joi is the id of the assistant");

        String authorization = session.authorization() != null ? session.authorization()
                : StringUtils.isBlank(hello.token()) ? null : "Bearer " + hello.token().strip();
        Principal principal;
        // nobody yet: what the services ask for the user of meanwhile must not open a session of the container
        try (ApiRequestAttributes anonymous = new ApiRequestAttributes(new User(), new SessionPoint());
             InRequestScope _ = new InRequestScope(anonymous)) {
            principal = joiApi.authenticate(authorization);
        }
        if (principal == null) {
            session.close(4001, "Valid credentials are needed: see GET " + JoiProtocol.API);
            closed(session);
            return;
        }
        ApiRequestAttributes attributes = new ApiRequestAttributes(principal.user(), new SessionPoint());
        Joi joi;
        String name;
        Date last;
        try (InRequestScope _ = new InRequestScope(attributes)) {
            joi = joiService.findForUser(hello.joi());
            if (joi == null || joi.isDeleted()) {
                attributes.close();
                session.close(4004, "No assistant " + hello.joi());
                closed(session);
                return;
            }
            name = joiService.name(joi);
            last = chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
        }
        session.welcome(principal, attributes, joi, hello);
        NodeSession before = registered.put(key(session), session);
        if (before != null && before != session) {
            before.close(4003, "The node connected again");
            closed(before);
        }
        session.send(NodeMessage.WELCOME, null, frame.id(), new Welcome(JoiProtocol.NODE_VERSION, "joi-server", JoiApi.version(),
                session.id(), new JoiSummary(joi.getUuid(), name, last == null ? null : last.toInstant().toString()),
                HEARTBEAT_SECONDS));
        log.info("Node {} ({}) connected for {}", hello.name(), hello.software(), joi.getUuid());
    }

    private void manifest(NodeSession session, Frame frame) throws Exception {
        Manifest manifest = data(frame, Manifest.class);
        require(manifest.tools() != null, "manifest.tools is the list of the tools (empty for none)");
        for (ToolDefinition tool : manifest.tools()) {
            require(tool != null && tool.name() != null && TOOL_NAME.matcher(tool.name()).matches(),
                    "A tool's name is lowercase letters, digits and _: " + (tool == null ? null : tool.name()));
            require(StringUtils.isNotBlank(tool.description()), "The tool " + tool.name() + " needs a description");
            require(tool.tier() != null, "The tool " + tool.name() + " needs a tier: allow, ask, deny or depends");
        }
        if (session.offer(manifest) && greeting) {
            greet(session);
        }
    }

    /**
     * Her turn as the node connects: her very first waking up, or the greeting of the user who is back (not for a node
     * that only lost the connection, {@code hello.resumed}).
     */
    private void greet(NodeSession session) throws Exception {
        Joi joi;
        boolean first;
        String text;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            joi = joiService.find(session.joiId());
            if (joi == null || chatService.chatModel(joi) == null) {
                // nobody to greet with: the node shows her as she is
                return;
            }
            first = !chatMessageService.hasAny(joi);
            if (!first && Boolean.TRUE.equals(session.hello().resumed())) {
                return;
            }
            NodeEventTemplateData data = new NodeEventTemplateData();
            data.setNode(session.name());
            data.setNodeKind(StringUtils.defaultIfBlank(session.hello().kind(), null));
            Date last = first ? null : chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
            data.setLastChatted(last == null ? null : ago(last.toInstant(), Instant.now()));
            text = personaService.render(joi, first ? JoiPromptField.FIRST_BOOT_EVENT : JoiPromptField.CONNECTED_EVENT, data).strip();
        }
        String turn = "s-" + UUID.randomUUID().toString().substring(0, 12);
        session.send(NodeMessage.TURN, null, null, new TurnStart(turn, first ? FIRST_BOOT : CONNECTED));
        startTurn(session, turn, joi, new TurnInput(text, session.name(), session.speaks(), true,
                first ? "node.first_boot" : "node.connected"), false);
    }

    /** She is shown on a Looking Glass now, or no longer: her turn about it, so she can react. */
    private void display(NodeSession session, Frame frame) throws Exception {
        Display display = data(frame, Display.class);
        Joi joi;
        String text;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            joi = joiService.find(session.joiId());
            if (joi == null || chatService.chatModel(joi) == null) {
                return;
            }
            NodeEventTemplateData data = new NodeEventTemplateData();
            data.setNode(session.name());
            data.setNodeKind(StringUtils.defaultIfBlank(session.hello().kind(), null));
            text = personaService.render(joi, display.lookingGlass() ? JoiPromptField.LOOKING_GLASS_ENTERED_EVENT
                    : JoiPromptField.LOOKING_GLASS_EXITED_EVENT, data).strip();
        }
        String cause = display.lookingGlass() ? LOOKING_GLASS_ENTERED : LOOKING_GLASS_EXITED;
        String turn = "s-" + UUID.randomUUID().toString().substring(0, 12);
        session.send(NodeMessage.TURN, null, null, new TurnStart(turn, cause));
        startTurn(session, turn, joi, new TurnInput(text, session.name(), session.speaks(), true, "node." + cause), false);
    }

    /** How long ago, in words she can say. */
    static String ago(Instant then, Instant now) {
        Duration since = Duration.between(then, now);
        if (since.toMinutes() < 2) {
            return "a moment ago";
        }
        if (since.toHours() < 2) {
            return since.toMinutes() + " minutes ago";
        }
        if (since.toDays() < 2) {
            return since.toHours() + " hours ago";
        }
        return since.toDays() + " days ago";
    }

    private void say(NodeSession session, Frame frame) throws Exception {
        require(frame.id() != null && ID.matcher(frame.id()).matches(), "A say needs an id: it is the id of the turn");
        Say say = data(frame, Say.class);
        require(StringUtils.isNotBlank(say.text()), "say.text is what the user said");
        Joi joi;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            joi = joiService.find(session.joiId());
        }
        startTurn(session, frame.id(), joi, new TurnInput(say.text(), session.name(), say.speakOrDefault(), true),
                say.reasoningOrDefault());
    }

    /** Runs her turn in the connection's request scope; its frames refer to the turn's id. */
    private void startTurn(NodeSession session, String turn, Joi joi, TurnInput input, boolean reasoning) throws Exception {
        CompletableFuture<ChatService.Result> result;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            result = chatService.chat(joi, input, new ChatService.Listener() {
                @Override
                public void onReasoning(String delta) {
                    if (reasoning) {
                        session.send(NodeMessage.REASONING, null, turn, new TextDelta(delta));
                    }
                }

                @Override
                public void onText(String delta) {
                    session.send(NodeMessage.TEXT, null, turn, new TextDelta(delta));
                }

                @Override
                public void onToolCall(String name, String arguments, String result, boolean failed) {
                    session.send(NodeMessage.TOOL, null, turn, new ToolUse(name, StringUtils.defaultIfBlank(arguments, "{}"), result, !failed));
                }

                @Override
                public void onReply(ChatMessage message) {
                    session.send(NodeMessage.REPLY, null, turn, JoiApi.reply(message, reasoning, null, null, -1));
                }

                @Override
                public void onAudio(int index, String text, byte[] audio) {
                    session.send(NodeMessage.AUDIO, null, turn, new AudioChunk(index, text, AudioKind.of(audio).mime(),
                            Base64.getEncoder().encodeToString(audio)));
                }
            });
        } catch (NoModelException e) {
            session.error(turn, "no_model", e.getMessage());
            return;
        }
        session.turnStarted(turn, result);
        result.whenComplete((done, error) -> {
            session.turnEnded(turn);
            // a call of the turn still open is no longer wanted
            for (Map.Entry<String, PendingCall> call : List.copyOf(session.calls().entrySet())) {
                if (turn.equals(call.getValue().turn())) {
                    session.cancel(call.getKey(), "The turn was stopped");
                }
            }
            if (error == null) {
                session.send(NodeMessage.DONE, null, turn, new Done(done.message().getUuid(), done.speechError()));
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof CancellationException) {
                session.error(turn, "stopped", "The turn was stopped");
            } else {
                log.warn("Node {}: the turn {} failed", session.name(), turn, cause);
                Throwable root = ExceptionUtils.getRootCause(cause);
                Throwable shown = root == null ? cause : root;
                session.error(turn, "model_failed", StringUtils.defaultIfBlank(shown.getMessage(), shown.getClass().getSimpleName()));
            }
        });
    }

    private void stop(NodeSession session, Frame frame) {
        Stop stop = frame.data() == null ? new Stop(null) : data(frame, Stop.class);
        String current = session.turnId();
        CompletableFuture<?> turn = session.turn();
        if (turn == null || stop.turn() != null && !stop.turn().equals(current)) {
            session.error(frame.id(), "not_found", "No turn " + StringUtils.defaultString(stop.turn(), "runs") + " of this node");
            return;
        }
        turn.cancel(true);
    }

    private PendingCall call(NodeSession session, Frame frame, String callId) {
        PendingCall call = callId == null ? null : session.calls().get(callId);
        if (call == null) {
            session.error(frame.id(), "not_found", "No call " + callId);
        }
        return call;
    }

    private void progress(NodeSession session, Frame frame) {
        ToolProgress progress = data(frame, ToolProgress.class);
        PendingCall call = call(session, frame, progress.callId());
        if (call != null) {
            call.touch();
        }
    }

    private void approval(NodeSession session, Frame frame) {
        Approval approval = data(frame, Approval.class);
        require(approval.state() != null, "approval.state is asked, allowed or denied");
        PendingCall call = call(session, frame, approval.callId());
        if (call != null) {
            // an asked approval waits for the user, however long
            call.asked()[0] = approval.state() == ApprovalState.ASKED;
            call.touch();
        }
    }

    private void result(NodeSession session, Frame frame) {
        ToolResult result = data(frame, ToolResult.class);
        PendingCall call = call(session, frame, result.callId());
        if (call == null) {
            return;
        }
        session.calls().remove(result.callId());
        if (result.ok()) {
            call.continuation().complete(StringUtils.defaultString(result.content()));
        } else {
            String why = StringUtils.defaultIfBlank(result.content(), "It failed");
            call.continuation().fail(result.error() == null ? why : result.error() + ": " + why);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // tools
    // ----------------------------------------------------------------------------------------------------------------

    /** The function of a node's tool, as she sees it ({@code desk__read_file}); null when its name is too long. */
    private FunctionDefinition function(NodeSession session, ToolDefinition tool) {
        String name = session.name() + "__" + tool.name();
        if (name.length() > MAX_FUNCTION_NAME) {
            log.warn("Node {}: the tool {} is left out, its name is longer than {}", session.name(), tool.name(), MAX_FUNCTION_NAME);
            return null;
        }
        String where = "On the node \"" + session.name() + "\""
                + (StringUtils.isBlank(session.hello().kind()) ? "" : " (" + session.hello().kind() + ")") + ": ";
        String tier = switch (tool.tier() == null ? ToolTier.ALLOW : tool.tier()) {
            case ASK -> " The user approves each use there.";
            case DENY -> " Not allowed there now: ask the user to allow it.";
            case DEPENDS -> " The user may be asked to approve it there.";
            case ALLOW -> "";
        };
        return FunctionDefinition.dynamic(name, where + tool.description() + tier, schema(tool), (continuation, arguments) -> {
            if (session.closed()) {
                continuation.fail("The node " + session.name() + " is gone");
                return;
            }
            String callId = "c-" + UUID.randomUUID().toString().substring(0, 12);
            JsonObject json = arguments == null ? new JsonObject() : NodeSession.GSON.toJsonTree(arguments).getAsJsonObject();
            continuation.onCancel(() -> session.cancel(callId, "The turn was stopped"));
            session.call(callId, tool.name(), json, session.turnId(), TOOL_TIMEOUT_SECONDS, continuation);
        });
    }

    private ObjectNode schema(ToolDefinition tool) {
        try {
            JsonNode node = tool.parameters() == null ? null : jackson.readTree(NodeSession.GSON.toJson(tool.parameters()));
            if (node instanceof ObjectNode object) {
                if (!object.has("type")) {
                    object.put("type", "object");
                }
                return object;
            }
        } catch (Exception e) {
            log.debug("The schema of {} is not JSON: {}", tool.name(), e.getMessage());
        }
        ObjectNode empty = jackson.createObjectNode();
        empty.put("type", "object");
        return empty;
    }
}
