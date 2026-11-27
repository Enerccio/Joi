package com.github.enerccio.joi.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.api.JoiApi;
import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.ToolEffects;
import com.github.enerccio.joi.domain.chat.ToolImages;
import com.github.enerccio.joi.domain.chat.ChatService.NoModelException;
import com.github.enerccio.joi.domain.chat.ChatService.NoVisionException;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.web.TaskInfos;
import com.github.enerccio.joi.protocol.http.TaskInfo;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.events.QueuedEventPlan;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.node.NodeSession;
import com.github.enerccio.joi.domain.node.NodeSession.PendingCall;
import com.github.enerccio.joi.domain.node.NodeTransport;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.persona.MoodFace;
import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.domain.templates.NodeEventTemplateData;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.AudioChunk;
import com.github.enerccio.joi.protocol.http.Done;
import com.github.enerccio.joi.protocol.http.JoiSummary;
import com.github.enerccio.joi.protocol.http.TextDelta;
import com.github.enerccio.joi.protocol.node.*;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.function.FunctionContinuation;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import com.github.enerccio.joi.protocol.http.ChatLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /** The cause of the turn when the server was down and a node connected to it again. */
    public static final String SERVER_RESTARTED = "server_restarted";
    /** The causes of her turns as the user moves her onto a Looking Glass and back ({@code display}). */
    public static final String LOOKING_GLASS_ENTERED = "looking_glass_entered";
    public static final String LOOKING_GLASS_EXITED = "looking_glass_exited";
    /** The cause of her turn as the user switches a part of her body on the node ({@code part}). */
    public static final String PART_SWITCHED = "part_switched";
    /** The cause of the turn about a task of hers that ended (a search, a page): the node plays it as a plain turn. */
    public static final String TASK_ENDED = "task_ended";
    /**
     * The cause of her turn about a summary that was made (a node that does not know it plays a plain turn): the messages of
     * that turn do not make a summary of their own.
     */
    public static final String SUMMARY_CREATED = "summary_created";
    /** The cause of her turn about what happened while no node of hers was connected (since 1.7). */
    public static final String EVENTS_WAITED = "events_waited";
    /** The tool she decides on the user's requests to change her parts with ({@code PART_REQUEST_EVENT}). */
    public static final String DECIDE_PART_CHANGE = "decide_part_change";

    @Autowired
    private ToolImages toolImages;

    @Autowired
    private JoiApi joiApi;

    @Autowired
    private JoiService joiService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private QueuedEventService queuedEventService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private JoiTaskService joiTaskService;

    @Autowired
    private PersonaService personaService;

    /** Lazy: the summarizer tells her through this service. */
    @Autowired
    @Lazy
    private SummarizerService summarizerService;

    private final ObjectMapper jackson = new ObjectMapper();
    /** When this run of the server started: a node that lost the connection before it and is back found it down. */
    private final Instant startedAt = Instant.now();
    /** The assistants told that the server was down since it started (once for her, however many nodes come back). */
    private final Set<Long> restartTold = ConcurrentHashMap.newKeySet();
    /** Every open connection, welcomed or not. */
    private final Set<NodeSession> open = ConcurrentHashMap.newKeySet();
    /** The welcomed ones by "user/assistant/node id": a node connecting again replaces its old connection. */
    private final Map<String, NodeSession> registered = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticks;
    private Registration tools;
    private Registration logs;
    private Registration moods;
    /** Whether the server greets a node that connected (off in tests that drive the turns themselves). */
    private volatile boolean greeting = true;
    /** How long after the last event for her the server waits for more before it tells her (the ones that come together are told together). */
    private volatile long gatherMillis = 1500;
    /**
     * How long her greeting waits at most for the summaries that are due as the node connects (a day that rolled while the
     * server was off): she greets the user with them, in one turn; what takes longer is told in a turn of its own after.
     */
    private volatile long summaryWaitMillis = 180_000;

    @Override
    public void afterPropertiesSet() {
        ticks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Joi nodes");
            thread.setDaemon(true);
            return thread;
        });
        ticks.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        logs = chatService.watchLogs(this::sendLog);
        moods = chatService.watchMoods(this::mood);
        tools = chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            List<FunctionDefinition> functions = new ArrayList<>();
            // two nodes of hers with the same name would offer the same function twice, which a model API refuses: the first wins
            Set<String> names = new HashSet<>();
            for (NodeSession session : sessions(event.getJoi().getId())) {
                for (ToolDefinition tool : session.tools()) {
                    FunctionDefinition function = function(session, tool);
                    if (function != null && names.add(function.getName())) {
                        functions.add(function);
                    } else if (function != null) {
                        log.warn("Node {}: the tool {} is left out, another node of hers has the same name", session.name(), tool.name());
                    }
                }
            }
            // always offered, with a request open or not (and with a node or not): the model renders the tools before the history, so a
            // tool that comes and goes makes the server's cache of the prompt start over; with no request open it answers so
            functions.add(decidePartChange(event.getJoi().getId()));
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
        if (logs != null) {
            logs.unregister();
        }
        if (moods != null) {
            moods.unregister();
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

    /** How long the server gathers events for her before it tells her, in milliseconds; tests make it short. */
    public void setGatherMillis(long gatherMillis) {
        this.gatherMillis = gatherMillis;
    }

    /** How long her greeting waits at most for the summaries that are due, in milliseconds. */
    public void setSummaryWaitMillis(long summaryWaitMillis) {
        this.summaryWaitMillis = summaryWaitMillis;
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
        session.markClosed();
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
            // a node that closed or crashed does not stop her turn once the turn has written something or called a function that
            // changes state (not a gesture): it finishes, her words and her voice are kept in the chat, and its frames go nowhere.
            // A turn that has only thought so far is dropped. The turn works in the connection's request scope: closed after it
            if (!session.produced()) {
                turn.cancel(true);
            }
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

    @Override
    public List<NodeSession> sessionsWithTool(Joi joi, String tool) {
        return sessions(joi).stream().filter(session -> session.tools().stream().anyMatch(t -> tool.equals(t.name())))
                .sorted(java.util.Comparator.comparing((NodeSession session) -> session.turnId() == null)).toList();
    }

    @Override
    public void callTool(NodeSession session, String tool, JsonObject arguments, FunctionContinuation<Object> continuation) {
        if (session.closed()) {
            continuation.fail("The node " + session.name() + " is gone");
            return;
        }
        String callId = "c-" + UUID.randomUUID().toString().substring(0, 12);
        continuation.onCancel(() -> session.cancel(callId, "The turn was stopped"));
        session.call(callId, tool, arguments == null ? new JsonObject() : arguments, session.turnId(), TOOL_TIMEOUT_SECONDS, continuation);
    }

    private List<NodeSession> sessions(Long joiId) {
        return registered.values().stream().filter(s -> joiId.equals(s.joiId()) && !s.closed()).toList();
    }

    @Override
    public boolean tell(Joi joi, String text, String cause) {
        List<NodeSession> connected = sessions(joi);
        if (connected.isEmpty() || StringUtils.isBlank(text)) {
            return false;
        }
        // one node tells her: the one whose greeting waits (it goes with the greeting), else the one the user did something on last
        // (that speaks: her answer is voiced there, and she is in the profile of that node), else the one used last
        java.util.Comparator<NodeSession> recent = java.util.Comparator.comparingLong(NodeSession::lastActive);
        NodeSession session = connected.stream().filter(NodeSession::held).findFirst()
                .or(() -> connected.stream().filter(NodeSession::speaks).max(recent))
                .orElseGet(() -> connected.stream().max(recent).orElse(connected.getFirst()));
        pend(session, new NodeSession.Pending(text, null, cause));
        return true;
    }

    @Override
    public void notify(Joi joi, String event) {
        notify(joi, event, null);
    }

    @Override
    public void notify(Joi joi, String event, String profile) {
        Profiles set = profiles(joi.getId());
        for (NodeSession session : sessions(joi)) {
            if (profile == null || Profiles.idOf(profile).equals(set.profileOf(nodeId(session)))) {
                session.send(NodeMessage.EVENT, null, null, new ServerEvent(event, Instant.now().toString()));
            }
        }
    }

    @Override
    public String profileOf(NodeSession session) {
        return profiles(session.joiId()).profileOf(nodeId(session));
    }

    private static String nodeId(NodeSession session) {
        return session.hello() == null ? null : session.hello().nodeId();
    }

    /** Her profiles as stored now; none when they can't be read. */
    private Profiles profiles(Long joiId) {
        try {
            Joi stored = joiService.find(joiId);
            return stored == null ? new Profiles() : stored.profileSet();
        } catch (Exception e) {
            log.debug("The profiles of {} were not read: {}", joiId, e.getMessage());
            return new Profiles();
        }
    }

    /** A note was written into the conversation of an assistant: her nodes are sent it. */
    private void sendLog(ChatMessage message) {
        ChatLog data = new ChatLog(message.getUuid(), message.getSentAt().toInstant().toString(), StringUtils.defaultString(message.getEventKind()),
                StringUtils.defaultString(message.getInput()));
        for (NodeSession session : sessions(message.getJoi().getId())) {
            session.send(NodeMessage.LOG, null, null, data);
        }
    }

    @Override
    public void task(Joi joi, TaskInfo task) {
        for (NodeSession session : sessions(joi.getId())) {
            session.send(NodeMessage.TASK, null, null, task);
        }
    }

    /** The tasks that wait for an answer are asked to a node that connects: it may have come after they were made. */
    private void sendWaitingTasks(NodeSession session) {
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            if (joi == null) {
                return;
            }
            for (JoiTask task : joiTaskService.findByStatus(joi, List.of(JoiTaskStatus.WAITING))) {
                session.send(NodeMessage.TASK, null, null, TaskInfos.of(task, null));
            }
        } catch (Exception e) {
            log.warn("Node {}: the tasks that wait were not sent", session.name(), e);
        }
    }

    @Override
    public void mood(Joi joi) {
        List<NodeSession> nodes = sessions(joi);
        if (nodes.isEmpty()) {
            return;
        }
        try {
            MoodState state = moodState(joi);
            for (NodeSession session : nodes) {
                session.send(NodeMessage.MOOD, null, null, state);
            }
        } catch (Exception e) {
            log.warn("The mood of {} was not sent to her nodes", joi.getUuid(), e);
        }
    }

    /** Her mood and its face as the user in scope has them set. */
    private MoodState moodState(Joi joi) throws Exception {
        Mood mood = joi.getMood() == null ? null : personaService.mood(joi.getMood());
        if (mood == null) {
            return new MoodState(null, null, null, null, null);
        }
        MoodFace face = mood.face();
        return new MoodState(mood.key(), mood.name(), face.expression(), face.expression() == null ? null : face.weight(), face.eyes());
    }

    /** A node that sent its manifest is told her mood. */
    private void sendMood(NodeSession session) {
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            if (joi != null) {
                session.send(NodeMessage.MOOD, null, null, moodState(joi));
            }
        } catch (Exception e) {
            log.warn("Node {}: the mood was not sent", session.name(), e);
        }
    }

    @Override
    public int animate(Joi joi, String animation, String name, boolean loop) {
        List<NodeSession> nodes = sessions(joi);
        for (NodeSession session : nodes) {
            session.send(NodeMessage.ANIMATION, null, null, new PlayAnimation(animation, name, loop ? Boolean.TRUE : null));
        }
        return nodes.size();
    }

    @Override
    public int express(Joi joi, com.github.enerccio.joi.protocol.node.ShowExpression expression) {
        List<NodeSession> nodes = sessions(joi);
        for (NodeSession session : nodes) {
            session.send(NodeMessage.EXPRESSION, null, null, expression);
        }
        return nodes.size();
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
        if (session.welcomed() && type != NodeMessage.HELLO && type != NodeMessage.PING && type != NodeMessage.PONG && !assistantExists(session)) {
            // she was deleted (or put in the trash) while the node was connected: nothing more is done for her
            session.error(frame.id(), "not_found", "The assistant " + session.joiUuid() + " was deleted");
            session.close(4004, "No assistant " + session.joiUuid());
            closed(session);
            return;
        }
        try {
            switch (type) {
                case HELLO -> hello(session, frame);
                case MANIFEST -> manifest(session, frame);
                case SAY -> {
                    session.active();
                    say(session, frame);
                }
                case STOP -> stop(session, frame);
                case DISPLAY -> {
                    session.active();
                    display(session, frame);
                }
                case PART -> {
                    session.active();
                    partSwitch(session, frame);
                }
                case OUTFIT -> {
                    session.active();
                    outfitSwitch(session, frame);
                }
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

    /** Whether the session's assistant is still there, not deleted; when that can't be told, she is taken as there. */
    private boolean assistantExists(NodeSession session) {
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            return joi != null && !joi.isDeleted();
        } catch (Exception e) {
            log.warn("Node {}: could not look up the assistant {}", session.name(), session.joiId(), e);
            return true;
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
            try {
                // remembered by its name: the user assigns it to a profile in her dialog
                profileService.seen(joi, hello.nodeId(), hello.name());
            } catch (Exception e) {
                log.debug("The node {} was not remembered: {}", hello.name(), e.getMessage());
            }
        }
        session.welcome(principal, attributes, joi, hello);
        NodeSession before = registered.put(key(session), session);
        if (before != null && before != session) {
            before.close(4003, "The node connected again");
            closed(before);
        }
        session.send(NodeMessage.WELCOME, null, frame.id(), new Welcome(JoiProtocol.NODE_VERSION, "joi-server", JoiApi.version(),
                session.id(), new JoiSummary(joi.getUuid(), name, last == null ? null : last.toInstant().toString()),
                HEARTBEAT_SECONDS, startedAt.toString()));
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
        boolean greets = session.offer(manifest) && greeting;
        if (greets) {
            // what waited for her and the summaries due now are told with her greeting, in one turn
            session.hold();
        }
        deliverWaiting(session);
        if (greets) {
            greet(session);
        }
        sendWaitingTasks(session);
        sendMood(session);
    }

    /**
     * Her turn as the node connects: her very first waking up, or the greeting of the user who is back (not for a node
     * that only lost the connection, {@code hello.resumed}).
     */
    private void greet(NodeSession session) throws Exception {
        Greeting greeting;
        CompletableFuture<Void> settled;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            greeting = greeting(session);
            // the summaries due now (a day that rolled while the server was off) are made first: she greets the user with them
            settled = greeting == null ? null : summarizerService.settle(joiService.find(session.joiId()));
        } catch (Exception e) {
            released(session);
            throw e;
        }
        if (greeting == null) {
            released(session);
            return;
        }
        settled.orTimeout(summaryWaitMillis, TimeUnit.MILLISECONDS)
                .whenComplete((done, error) -> ticks.execute(() -> greetNow(session, greeting)));
    }

    /** Her greeting: the text she is told and the cause of the turn. */
    private record Greeting(String text, String cause) {
    }

    /** What she is told as the node connects; null when she is not greeted (no model, or a node that only lost the connection). */
    private Greeting greeting(NodeSession session) throws Exception {
        Joi joi = joiService.find(session.joiId());
        if (joi == null) {
            return null;
        }
        if (chatService.chatModel(joi) == null) {
            // nobody to greet with: the node shows her as she is, and is told why she says nothing (the same error as a say gets)
            session.error(null, "no_model", joiService.name(joi)
                    + " has no chat model: choose one in her About tab or in User Settings");
            return null;
        }
        boolean first = !chatMessageService.hasAny(joi);
        boolean restarted = false;
        if (!first && Boolean.TRUE.equals(session.hello().resumed())) {
            // a node that only lost the connection is not greeted; one that found the server down is, once for her
            restarted = wasDown(session.hello().resume()) && restartTold.add(joi.getId());
            if (!restarted) {
                return null;
            }
        }
        NodeEventTemplateData data = new NodeEventTemplateData();
        data.setNode(session.name());
        data.setNodeKind(StringUtils.defaultIfBlank(session.hello().kind(), null));
        Date last = first ? null : chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
        Instant now = Instant.now();
        if (last != null) {
            data.setLastChatted(ago(last.toInstant(), now));
            // the clock times too: a night between them is a night's sleep, a morning the start of a working day
            ZoneId zone = AppClock.INSTANCE.getZone();
            data.setLastChattedAt(clockTime(last.toInstant(), now, zone));
            data.setBackAt(clockTime(now, now, zone));
        }
        if (restarted) {
            Resume resume = session.hello().resume();
            data.setHowLost(Integer.valueOf(1001).equals(resume.closeCode()) ? "was shut down" : "went down");
            data.setDownFor(downFor(resume.lostAt(), now));
        }
        String text = personaService.render(joi, first ? JoiPromptField.FIRST_BOOT_EVENT
                : restarted ? JoiPromptField.SERVER_RESTARTED_EVENT : JoiPromptField.CONNECTED_EVENT, data).strip();
        return new Greeting(text, first ? FIRST_BOOT : restarted ? SERVER_RESTARTED : CONNECTED);
    }

    /**
     * A moment as she can say it: the day and the time ({@code Sunday 2:44 AM}), with the date when it is more than six days
     * before {@code now}.
     */
    public static String clockTime(Instant moment, Instant now, ZoneId zone) {
        String pattern = Duration.between(moment, now).toDays() > 6 ? "EEEE, MMMM d, h:mm a" : "EEEE h:mm a";
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(moment.atZone(zone));
    }

    /** The greeting is not held any more: what waited for it is told as usual. */
    private void released(NodeSession session) {
        session.release();
        if (session.hasPending()) {
            ticks.schedule(() -> tell(session), 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Her greeting, now that the summaries are made (or took too long): one turn with what waited for her and the
     * summaries first, the greeting last. Not when the user spoke first: the words took it all along.
     */
    private void greetNow(NodeSession session, Greeting greeting) {
        if (!session.release()) {
            if (session.hasPending()) {
                tell(session);
            }
            return;
        }
        List<NodeSession.Pending> pending = session.takeAllPending();
        List<Integer> asked = pending.stream().map(NodeSession.Pending::request).filter(Objects::nonNull).toList();
        String turn = "s-" + UUID.randomUUID().toString().substring(0, 12);
        boolean started = false;
        try {
            Joi joi;
            String input;
            try (InRequestScope _ = new InRequestScope(session.attributes())) {
                joi = joiService.find(session.joiId());
                input = pending.isEmpty() ? greeting.text() : told(joi, pending) + "\n\n" + greeting.text();
            }
            if (joi != null) {
                session.told(asked, turn);
                session.send(NodeMessage.TURN, null, null, new TurnStart(turn, greeting.cause()));
                started = startTurn(session, turn, joi, new TurnInput(input, session.name(), session.speaks(), true,
                        "node." + greeting.cause()), false);
            }
        } catch (Exception e) {
            log.warn("Node {}: her greeting failed", session.name(), e);
        }
        if (!started) {
            decideLeft(session, turn);
        }
    }

    /**
     * What happened while no node of hers was connected (the events that waited, see {@code DESIGN.md} → Events): told now,
     * in a turn after the greeting, <b>merged</b> ({@link QueuedEventPlan}: a part switched five times is one change, and one
     * that ended where it began is nothing) and without what no longer matters. A task's result is told as it ended.
     */
    private void deliverWaiting(NodeSession session) {
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            if (joi == null) {
                return;
            }
            List<QueuedEvent> waiting = queuedEventService.findWaiting(joi);
            if (waiting.isEmpty()) {
                return;
            }
            QueuedEventPlan.Result plan = QueuedEventPlan.of(waiting, Instant.now(), uuid -> {
                try {
                    Body body = bodyService.findForUser(uuid);
                    return body != null && !body.isDeleted();
                } catch (Exception e) {
                    return true;
                }
            });
            for (QueuedEvent event : plan.dropped()) {
                queuedEventService.markDelivered(event, null);
            }
            for (QueuedEventPlan.Told told : plan.told()) {
                for (QueuedEvent event : told.sources()) {
                    queuedEventService.markDelivered(event, null);
                }
                pend(session, new NodeSession.Pending(told.text(), null, JoiEvents.TASK_ENDED.equals(told.kind()) ? TASK_ENDED : EVENTS_WAITED));
            }
        } catch (Exception e) {
            log.warn("Node {}: what waited for her was not told to her", session.name(), e);
        }
    }

    /** Whether the server started since the connection the node lost: it was down, whatever the reason. */
    private boolean wasDown(Resume resume) {
        return resume != null && StringUtils.isNotBlank(resume.serverStartedAt()) && !resume.serverStartedAt().equals(startedAt.toString());
    }

    /** How long the server was gone, from when the node lost the connection, in words; null when the node could not say. */
    static String downFor(String lostAt, Instant now) {
        if (StringUtils.isBlank(lostAt)) {
            return null;
        }
        Duration gone;
        try {
            gone = Duration.between(Instant.parse(lostAt), now);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        if (gone.isNegative() || gone.toMinutes() < 1) {
            return "less than a minute";
        }
        if (gone.toHours() < 1) {
            return plural(gone.toMinutes(), "minute");
        }
        if (gone.toDays() < 1) {
            return plural(gone.toHours(), "hour");
        }
        return plural(gone.toDays(), "day");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
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
        if (session.held()) {
            // her greeting waits for the summaries: this goes with it, not in a turn of its own beside it
            pend(session, new NodeSession.Pending(text, null, cause));
            return;
        }
        String turn = "s-" + UUID.randomUUID().toString().substring(0, 12);
        session.send(NodeMessage.TURN, null, null, new TurnStart(turn, cause));
        startTurn(session, turn, joi, new TurnInput(text, session.name(), session.speaks(), true, "node." + cause), false);
    }

    /**
     * The user put a part of her body in another state on the node. When her personality lets her decide
     * (her personality has words about it) it is a request: the body stays as it is until she allows it (the node is
     * answered then). Else it is kept on the body at once (the same at the next start and on every node), answered by
     * {@code part_kept} when the frame has an id, and, when she is told (she has chatted and the part changed), the
     * event waits for her turn about it ({@link #pend}), so what comes together is told together.
     */
    private void partSwitch(NodeSession session, Frame frame) throws Exception {
        PartSwitch change = data(frame, PartSwitch.class);
        require(StringUtils.isNotBlank(change.part()) && StringUtils.isNotBlank(change.state()), "part and state are what was switched");
        require(frame.id() == null || ID.matcher(frame.id()).matches(), "The id of a part is up to 64 letters, digits and ._:-");
        String text = null;
        boolean kept = true;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            String profile = joi == null ? null : profileOf(session);
            if (joi != null) {
                // what she is doing now is in this profile: what changes in it is told to her as it happens
                profileService.used(joi, profile);
            }
            Body body = joi == null ? null : bodyService.active(joi, profile);
            require(body != null, "She is in no body");
            boolean told = chatService.chatModel(joi) != null && chatMessageService.hasAny(joi);
            if (told && personaService.personalityOf(joi).decidesOnParts()) {
                BodyService.PartRequest request;
                try {
                    request = bodyService.partRequest(body, profile, change.part(), change.state());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Her body has no part " + change.part() + " in the state " + change.state());
                }
                if (request != null) {
                    NodeSession.PartRequest asked = session.request(frame.id(), change.part(), change.state());
                    JoiDefaults defaults = personaService.defaults();
                    pend(session, new NodeSession.Pending(defaults.eventPartRequested(asked.number(), request.part(), request.state(),
                            request.group(), request.sentence()), asked.number()));
                    // answered when she decided
                    return;
                }
                // nothing to decide: it is as it was asked
            } else {
                List<QueuedEvent> events;
                try {
                    events = bodyService.switchPart(body, profile, change.part(), change.state());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Her body has no part " + change.part() + " in the state " + change.state());
                }
                QueuedEvent event = events.isEmpty() ? null : events.getFirst();
                if (event != null) {
                    // told in the turn that follows: it does not wait for another delivery
                    queuedEventService.markDelivered(event, null);
                    if (told) {
                        text = event.getText();
                    }
                }
            }
        }
        if (frame.id() != null) {
            session.send(NodeMessage.PART_KEPT, null, frame.id(), new PartKept(change.part(), change.state(), text != null));
        }
        if (text != null) {
            pend(session, new NodeSession.Pending(text, null));
        }
    }

    /**
     * The user put an outfit on, on a node: kept in the profile of the node, and she is told as for a part (it is a user's
     * change, told in the turn that follows with what else waits). The nodes of the profile read her body again (the event
     * {@code body}, the sender too); an outfit the body has not is an error.
     */
    private void outfitSwitch(NodeSession session, Frame frame) throws Exception {
        OutfitSwitch change = data(frame, OutfitSwitch.class);
        require(StringUtils.isNotBlank(change.outfit()), "outfit is what was put on");
        String text = null;
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            String profile = joi == null ? null : profileOf(session);
            if (joi != null) {
                profileService.used(joi, profile);
            }
            Body body = joi == null ? null : bodyService.active(joi, profile);
            require(body != null, "She is in no body");
            List<QueuedEvent> events;
            try {
                events = bodyService.wear(body, profile, change.outfit());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Her body has no outfit " + change.outfit());
            }
            QueuedEvent event = events.isEmpty() ? null : events.getFirst();
            if (event != null) {
                queuedEventService.markDelivered(event, null);
                if (chatService.chatModel(joi) != null && chatMessageService.hasAny(joi)) {
                    text = event.getText();
                }
            }
        }
        if (text != null) {
            pend(session, new NodeSession.Pending(text, null));
        }
    }

    /** She is to be told: with what else is waiting, once nothing more comes for a moment and no turn of hers runs. */
    private void pend(NodeSession session, NodeSession.Pending item) {
        session.pend(item, () -> ticks.schedule(() -> tell(session), gatherMillis, TimeUnit.MILLISECONDS));
    }

    /** Her turn about what waits for her: one turn for all of it, in the order it happened. */
    private void tell(NodeSession session) {
        List<NodeSession.Pending> pending = session.takePending();
        if (pending.isEmpty()) {
            return;
        }
        String turn = "s-" + UUID.randomUUID().toString().substring(0, 12);
        List<Integer> asked = pending.stream().map(NodeSession.Pending::request).filter(Objects::nonNull).toList();
        boolean started = false;
        try {
            Joi joi;
            String input;
            try (InRequestScope _ = new InRequestScope(session.attributes())) {
                joi = joiService.find(session.joiId());
                input = told(joi, pending);
            }
            // a turn that is only about tasks that ended has that cause; about what waited, that one; else it is the usual one
            String cause = pending.stream().allMatch(item -> TASK_ENDED.equals(item.cause())) ? TASK_ENDED
                    : pending.stream().allMatch(item -> SUMMARY_CREATED.equals(item.cause())) ? SUMMARY_CREATED
                    : pending.stream().allMatch(item -> item.cause() != null) ? EVENTS_WAITED : PART_SWITCHED;
            session.told(asked, turn);
            session.send(NodeMessage.TURN, null, null, new TurnStart(turn, cause));
            started = startTurn(session, turn, joi, new TurnInput(input, session.name(), session.speaks(), true,
                    "node." + cause), false);
        } catch (Exception e) {
            log.warn("Node {}: telling her what happened failed", session.name(), e);
        }
        if (!started) {
            // she was not asked after all: what was asked is allowed
            decideLeft(session, turn);
        }
    }

    /**
     * What is told to her about what waited: the events (the changes already kept) first, then the requests for her
     * to decide on, with how she decides: her personality's words.
     */
    private String told(Joi joi, List<NodeSession.Pending> pending) throws Exception {
        String others = pending.stream().filter(item -> item.request() == null).map(NodeSession.Pending::text)
                .collect(Collectors.joining("\n"));
        String requests = pending.stream().filter(item -> item.request() != null).map(NodeSession.Pending::text)
                .collect(Collectors.joining("\n"));
        String decided = requests.isEmpty() ? null : personaService.renderPartRequests(joi, requests, others);
        return decided != null ? decided : (others.isEmpty() ? requests : others + (requests.isEmpty() ? "" : "\n" + requests));
    }

    /** What she left unanswered of the requests the turn told her is allowed: the body is the user's, and no answer is no refusal. */
    private void decideLeft(NodeSession session, String turn) {
        for (NodeSession.PartRequest left : session.takeUndecided(turn)) {
            try {
                answer(session, left, true, null);
            } catch (Exception e) {
                log.warn("Node {}: the change of {} was not kept", session.name(), left.part(), e);
            }
        }
    }

    /**
     * Answers a request of the user: allowed, the change is made on the body (and the node told it is kept); refused,
     * the node is told what she said.
     */
    private void answer(NodeSession session, NodeSession.PartRequest request, boolean allow, String reason) throws Exception {
        if (!allow) {
            if (request.frameId() != null) {
                session.send(NodeMessage.PART_REFUSED, null, request.frameId(), new PartRefused(request.part(), request.state(), reason));
            }
            return;
        }
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            Joi joi = joiService.find(session.joiId());
            String profile = joi == null ? null : profileOf(session);
            Body body = joi == null ? null : bodyService.active(joi, profile);
            if (body == null) {
                throw new IllegalArgumentException("She is in no body");
            }
            // she decided on it herself, it is not told to her again
            for (QueuedEvent event : bodyService.switchPart(body, profile, request.part(), request.state())) {
                queuedEventService.markDelivered(event, null);
            }
        } catch (IllegalArgumentException e) {
            if (request.frameId() != null) {
                session.error(request.frameId(), "bad_message", "Her body has no part " + request.part() + " in the state " + request.state());
            }
            return;
        }
        if (request.frameId() != null) {
            session.send(NodeMessage.PART_KEPT, null, request.frameId(), new PartKept(request.part(), request.state(), false));
        }
    }

    /** The tool she decides on the requests she was told with: the requests are looked for when she calls it. */
    private FunctionDefinition decidePartChange(Long joiId) {
        ObjectNode schema = jackson.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("request").put("type", "integer").put("description", "The number of the request you answer.");
        properties.putObject("decision").put("type", "string").put("description", "Whether you allow the change.")
                .putArray("enum").add("allow").add("refuse");
        properties.putObject("reason").put("type", "string")
                .put("description", "When you refuse: what you tell the user about it, in your own words.");
        schema.putArray("required").add("request").add("decision");
        return FunctionDefinition.dynamic(DECIDE_PART_CHANGE, "Answers a request of the user to change a part of your body: "
                + "allow it (the change is made) or refuse it (nothing changes, and the user sees why).", schema, (continuation, arguments) -> {
            JsonObject json = arguments == null ? new JsonObject() : NodeSession.GSON.toJsonTree(arguments).getAsJsonObject();
            String decision = json.has("decision") && json.get("decision").isJsonPrimitive() ? json.get("decision").getAsString() : "";
            if (!json.has("request") || !json.get("request").isJsonPrimitive() || !decision.equals("allow") && !decision.equals("refuse")) {
                continuation.fail("Give the number of the request and allow or refuse");
                return;
            }
            NodeSession session = sessions(joiId).stream().filter(NodeSession::hasOpenRequests).findFirst().orElse(null);
            NodeSession.PartRequest request = session == null ? null : session.take(json.get("request").getAsInt());
            if (request == null) {
                continuation.fail("There is no such request, or it was answered already");
                return;
            }
            String reason = json.has("reason") && json.get("reason").isJsonPrimitive() ? json.get("reason").getAsString() : null;
            try {
                answer(session, request, decision.equals("allow"), StringUtils.defaultIfBlank(reason, null));
                continuation.complete(decision.equals("allow") ? "Allowed: the change is made." : "Refused: nothing changes.");
            } catch (Exception e) {
                continuation.fail("It failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        });
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
        require(StringUtils.isNotBlank(say.text()) || !say.imagesOrEmpty().isEmpty(), "say.text is what the user said (or say.images has what she looks at)");
        Joi joi;
        String input = StringUtils.defaultString(say.text());
        List<Integer> asked = List.of();
        // the user spoke before she greeted them: no greeting, she answers the words (with what waited, below)
        session.release();
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            joi = joiService.find(session.joiId());
            if (say.withEventsOrDefault()) {
                // what the user did just before these words is told with them, as one turn: the words first
                List<NodeSession.Pending> pending = session.takeAllPending();
                if (!pending.isEmpty()) {
                    asked = pending.stream().map(NodeSession.Pending::request).filter(Objects::nonNull).toList();
                    input = StringUtils.isBlank(say.text()) ? told(joi, pending) : say.text() + "\n\n" + told(joi, pending);
                }
            }
        }
        session.told(asked, frame.id());
        if (!startTurn(session, frame.id(), joi, new TurnInput(input, session.name(), say.speakOrDefault(), true, null, say.imagesOrEmpty()),
                say.reasoningOrDefault())) {
            decideLeft(session, frame.id());
        }
    }

    /**
     * Runs her turn in the connection's request scope; its frames refer to the turn's id.
     *
     * @return false when it could not start (she has no model): the node was told
     */
    private boolean startTurn(NodeSession session, String turn, Joi joi, TurnInput node, boolean reasoning) throws Exception {
        // she is in the profile of the node that talks to her
        TurnInput input = node.withProfile(profileOf(session));
        CompletableFuture<ChatService.Result> result;
        session.produced(false);
        try (InRequestScope _ = new InRequestScope(session.attributes())) {
            result = chatService.chat(joi, input, new ChatService.Listener() {
                @Override
                public void onReasoning(String delta) {
                    // thinking that was not followed by anything is dropped with the connection
                    if (reasoning) {
                        session.send(NodeMessage.REASONING, null, turn, new TextDelta(delta));
                    }
                }

                @Override
                public void onText(String delta) {
                    session.produced(true);
                    session.send(NodeMessage.TEXT, null, turn, new TextDelta(delta));
                }

                @Override
                public void onToolStart(String name, String arguments) {
                    // a function that changes something is finished even when the node is gone; a gesture is not
                    if (ToolEffects.changesState(name)) {
                        session.produced(true);
                    }
                }

                @Override
                public void onToolCall(String name, String arguments, String result, boolean failed) {
                    session.send(NodeMessage.TOOL, null, turn, new ToolUse(name, StringUtils.defaultIfBlank(arguments, "{}"), result, !failed));
                }

                @Override
                public void onReply(ChatMessage message) {
                    session.produced(true);
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
            return false;
        } catch (NoVisionException e) {
            session.error(turn, "no_vision", e.getMessage());
            return false;
        }
        session.turnStarted(turn, result);
        result.whenComplete((done, error) -> {
            try {
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
            } finally {
                // what she did not answer is allowed, and what happened while it ran is told now, all together
                decideLeft(session, turn);
                if (session.hasPending()) {
                    ticks.schedule(() -> tell(session), 0, TimeUnit.MILLISECONDS);
                }
            }
        });
        return true;
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
        if (result.ok() && result.images() != null && !result.images().isEmpty()) {
            // a result is text: the pictures wait for the end of the round, when the turn shows them to her (CallToolsStep)
            toolImages.add(session.joiId(), session.name() + "__" + call.tool(),
                    result.images().stream().filter(StringUtils::isNotBlank).limit(ToolResult.MAX_IMAGES).toList());
        }
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
            callTool(session, tool.name(), arguments == null ? new JsonObject() : NodeSession.GSON.toJsonTree(arguments).getAsJsonObject(),
                    continuation);
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
