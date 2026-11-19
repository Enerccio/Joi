package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.ChatService.NoModelException;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.AccessTokenService.Principal;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.DeviceAuthorizationService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Joi's API ({@value JoiProtocol#API}, {@value JoiProtocol#OAUTH}, see {@code joi-protocol}): authenticates the call
 * (a token, an OAuth access token, or nobody when the configuration allows it), makes the request context of its user
 * ({@link ApiRequestAttributes}, no session of the container) and answers. The assistants, one of them, and chat with
 * her, whole or streamed as server-sent events.
 */
public class JoiApi implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JoiApi.class);

    static final int MAX_TEXT = 20_000;
    static final long KEEP_ALIVE_SECONDS = 15;
    static final String JSON = "application/json;charset=UTF-8";

    @Autowired
    private ApiConfiguration configuration;

    @Autowired
    private ApiAccessService apiAccessService;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private DeviceAuthorizationService deviceAuthorizationService;

    @Autowired
    private UserService userService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatService chatService;

    /** Nulls written out: a client sees every field of the schema. */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "joi-api-keep-alive");
        thread.setDaemon(true);
        return thread;
    });

    /** A call that ends in an error answer. */
    static final class ApiException extends Exception {
        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public void handle(ApiCall call, ApiSink sink) throws IOException {
        // nobody yet: what a service asks for the user of meanwhile must not open a session of the container
        try (ApiRequestAttributes anonymous = new ApiRequestAttributes(new User(), new SessionPoint());
             InRequestScope _ = new InRequestScope(anonymous)) {
            String path = StringUtils.removeEnd(call.path(), "/");
            if (path.equals(JoiProtocol.OAUTH_METADATA)) {
                metadata(call, sink);
            } else if (path.startsWith(JoiProtocol.OAUTH + "/")) {
                oauth(call, path, sink);
            } else if (path.equals(JoiProtocol.API)) {
                if (!"GET".equalsIgnoreCase(call.method())) {
                    error(sink, 405, "bad_request", "Use GET");
                } else {
                    json(sink, 200, serverInfo(call));
                }
            } else if (path.startsWith(JoiProtocol.API + "/")) {
                api(call, path.substring(JoiProtocol.API.length() + 1), sink);
            } else {
                error(sink, 404, "not_found", "Not found: " + call.path());
            }
        } catch (ApiException e) {
            error(sink, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            log.error("API: {} {} failed", call.method(), call.path(), e);
            error(sink, 500, "internal", "Internal error");
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the calls of a user
    // ----------------------------------------------------------------------------------------------------------------

    private void api(ApiCall call, String path, ApiSink sink) throws Exception {
        Principal principal = authenticate(call);
        if (principal == null) {
            sink.respond(401, JSON, body(new ApiError("unauthorized",
                            "Valid credentials are needed: Authorization: Bearer <token>, see GET " + JoiProtocol.API)),
                    Map.of("WWW-Authenticate", "Bearer realm=\"joi\""));
            return;
        }
        List<String> segments = List.of(path.split("/"));
        try (ApiRequestAttributes attributes = new ApiRequestAttributes(principal.user(), new SessionPoint());
             InRequestScope _ = new InRequestScope(attributes)) {
            try {
                route(call, segments, principal, sink);
            } catch (ApiException e) {
                error(sink, e.status, e.code, e.getMessage());
            } catch (NoModelException e) {
                error(sink, 409, "no_model", e.getMessage());
            } catch (IllegalArgumentException | JsonParseException e) {
                error(sink, 400, "bad_request", StringUtils.defaultIfBlank(e.getMessage(), "Bad request"));
            } catch (SecurityException e) {
                error(sink, 403, "forbidden", StringUtils.defaultIfBlank(e.getMessage(), "Forbidden"));
            }
        }
    }

    private void route(ApiCall call, List<String> path, Principal principal, ApiSink sink) throws Exception {
        boolean get = "GET".equalsIgnoreCase(call.method());
        boolean post = "POST".equalsIgnoreCase(call.method());
        if (get && path.equals(List.of("me"))) {
            User user = principal.user();
            json(sink, 200, new Me(user.getLogin(), StringUtils.defaultIfBlank(user.getFullName(), user.getLogin()), principal.method()));
        } else if (get && path.equals(List.of("jois"))) {
            json(sink, 200, jois());
        } else if (get && path.size() == 2 && path.getFirst().equals("jois")) {
            json(sink, 200, info(joi(path.get(1))));
        } else if (post && path.size() == 3 && path.getFirst().equals("jois") && path.get(2).equals("chat")) {
            chat(call, joi(path.get(1)), sink);
        } else {
            throw new ApiException(404, "not_found", "Not found: " + call.method() + " " + call.path());
        }
    }

    private JoiList jois() throws Exception {
        List<Joi> jois = joiService.findAllByName();
        Map<Long, Date> last = chatMessageService.findLastSentAt(jois);
        List<JoiSummary> list = new ArrayList<>();
        for (Joi joi : jois) {
            list.add(new JoiSummary(joi.getUuid(), joiService.name(joi), iso(last.get(joi.getId()))));
        }
        return new JoiList(list);
    }

    private JoiInfo info(Joi joi) throws Exception {
        JoiService.Persona persona = joiService.persona(joi);
        String name = persona.assistantName();
        Date last = chatMessageService.findLastSentAt(List.of(joi)).get(joi.getId());
        AI model = chatService.chatModel(joi);
        TtsModel tts = chatService.ttsModel(joi);
        Actor actor = chatService.voiceActor(joi);
        return new JoiInfo(joi.getUuid(), name, persona.userName(),
                persona.personalityKey() == null ? null : new Personality(persona.personalityKey(), persona.personalityName()),
                joi.getMood(), StringUtils.defaultIfBlank(joi.getWakeWord(), "Hey " + name), iso(last),
                model == null ? null : model.getName(), tts == null || actor == null ? null : new Voice(tts.getName(), actor.getName(),
                        StringUtils.defaultIfBlank(actor.getDescription(), null)));
    }

    private void chat(ApiCall call, Joi joi, ApiSink sink) throws Exception {
        ChatRequest request = gson.fromJson(call.text(), ChatRequest.class);
        if (request == null || StringUtils.isBlank(request.text())) {
            throw new IllegalArgumentException("The text is missing");
        }
        if (request.text().length() > MAX_TEXT) {
            throw new IllegalArgumentException("The text is longer than " + MAX_TEXT + " characters");
        }
        String accept = StringUtils.defaultString(call.header("Accept"));
        boolean stream = request.streamOrDefault() || accept.contains(JoiProtocol.EVENT_STREAM);
        if (chatService.chatModel(joi) == null) {
            throw new NoModelException(joiService.name(joi) + " has no chat model: choose one in her About tab or in User Settings");
        }
        if (stream) {
            stream(joi, request, sink.events());
            return;
        }
        ChatService.Result result;
        try {
            result = chatService.chat(joi, request.text(), request.node(), request.speakOrDefault(), false, new ChatService.Listener() {
            });
        } catch (NoModelException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("API: the chat of {} failed", joi.getUuid(), e);
            throw new ApiException(502, "model_failed", reason(e));
        }
        Audio audio = result.audio() == null ? null : new Audio(AudioKind.of(result.audio()).mime(), base64(result.audio()));
        json(sink, 200, reply(result.message(), request.reasoningOrDefault(), audio, result.speechError(), result.speechMs()));
    }

    private void stream(Joi joi, ChatRequest request, ApiSink.EventStream events) {
        boolean reasoning = request.reasoningOrDefault();
        ScheduledFuture<?> alive = keepAlive.scheduleAtFixedRate(() -> {
            try {
                events.comment("keep-alive");
            } catch (IOException e) {
                // the client is gone; the turn goes on and is stored
            }
        }, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        try {
            ChatService.Result result = chatService.chat(joi, request.text(), request.node(), request.speakOrDefault(), true,
                    new ChatService.Listener() {
                        @Override
                        public void onReasoning(String delta) throws Exception {
                            if (reasoning) {
                                send(events, StreamEvent.REASONING, new TextDelta(delta));
                            }
                        }

                        @Override
                        public void onText(String delta) throws Exception {
                            send(events, StreamEvent.TEXT, new TextDelta(delta));
                        }

                        @Override
                        public void onReply(ChatMessage message) throws Exception {
                            send(events, StreamEvent.REPLY, reply(message, reasoning, null, null, -1));
                        }

                        @Override
                        public void onAudio(int index, String text, byte[] audio) throws Exception {
                            send(events, StreamEvent.AUDIO, new AudioChunk(index, text, AudioKind.of(audio).mime(), base64(audio)));
                        }
                    });
            send(events, StreamEvent.DONE, new Done(result.message().getUuid(), result.speechError()));
        } catch (Exception e) {
            log.warn("API: the streamed chat of {} failed", joi.getUuid(), e);
            try {
                String code = e instanceof NoModelException ? "no_model" : e instanceof IllegalArgumentException ? "bad_request" : "model_failed";
                send(events, StreamEvent.ERROR, new ApiError(code, reason(e)));
            } catch (IOException gone) {
                // nobody to tell
            }
        } finally {
            alive.cancel(false);
        }
    }

    private ChatReply reply(ChatMessage message, boolean withReasoning, Audio audio, String speechError, long speechMs) {
        long request = message.getRequest() == null ? 0 : message.getRequest().getTime();
        long first = message.getTtft() == null ? -1 : message.getTtft().getTime() - request;
        long text = message.getResponseEnd() == null ? 0 : message.getResponseEnd().getTime() - request;
        Usage usage = new Usage(message.getPromptTokens() == null ? 0 : message.getPromptTokens(),
                message.getTokenCount() == null ? 0 : message.getTokenCount(), first, text, speechMs);
        return new ChatReply(message.getUuid(), message.getResponse(), withReasoning ? message.getResponseReasoning() : null,
                message.getMood(), message.getModelUsed(), audio, speechError, usage);
    }

    private Joi joi(String uuid) throws Exception {
        Joi joi = joiService.findForUser(uuid);
        if (joi == null || joi.isDeleted()) {
            throw new ApiException(404, "not_found", "No assistant " + uuid);
        }
        return joi;
    }

    /** Who the call acts for, null when nobody: its bearer credential, else the configured user of no auth. */
    Principal authenticate(ApiCall call) throws Exception {
        String authorization = call.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // a credential that is sent must be valid, also when no auth is on
            return accessTokenService.authenticate(authorization.substring(7).strip());
        }
        if (authorization != null || !configuration.isNoAuth()) {
            return null;
        }
        User user = noAuthUser();
        return user == null ? null : new Principal(user, null, AuthMethod.NONE);
    }

    private User noAuthUser() throws Exception {
        if (configuration.getNoAuthUser() != null) {
            User user = userService.findByName(configuration.getNoAuthUser());
            if (user == null || user.isDeleted()) {
                log.warn("API: the user of no auth ({}) does not exist", configuration.getNoAuthUser());
                return null;
            }
            return user;
        }
        return userService.findAll().stream().filter(u -> u.isAdmin() && !u.isDeleted()).min(Comparator.comparing(User::getId)).orElse(null);
    }

    private ServerInfo serverInfo(ApiCall call) throws Exception {
        List<AuthMethod> methods = apiAccessService.methods();
        String base = baseUrl(call);
        OAuthEndpoints oauth = methods.contains(AuthMethod.OAUTH)
                ? new OAuthEndpoints(base + JoiProtocol.DEVICE_AUTHORIZATION, base + JoiProtocol.TOKEN, base + "/?device")
                : null;
        return new ServerInfo(JoiProtocol.VERSION, "joi-server", version(), new AuthInfo(methods, oauth));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // OAuth
    // ----------------------------------------------------------------------------------------------------------------

    private void metadata(ApiCall call, ApiSink sink) throws Exception {
        if (!apiAccessService.get().oauth()) {
            error(sink, 404, "not_found", "OAuth is not offered");
            return;
        }
        String base = baseUrl(call);
        json(sink, 200, new OAuthMetadata(base, base + JoiProtocol.DEVICE_AUTHORIZATION, base + JoiProtocol.TOKEN,
                List.of(JoiProtocol.DEVICE_CODE_GRANT, "refresh_token"), List.of("none")));
    }

    private void oauth(ApiCall call, String path, ApiSink sink) throws Exception {
        if (!"POST".equalsIgnoreCase(call.method())) {
            oauthError(sink, 405, "invalid_request", "Use POST");
            return;
        }
        if (!apiAccessService.get().oauth()) {
            oauthError(sink, 503, "unavailable", "OAuth is turned off on this server");
            return;
        }
        Map<String, String> form = call.form();
        if (path.equals(JoiProtocol.DEVICE_AUTHORIZATION)) {
            if (StringUtils.isBlank(form.get("client_id"))) {
                oauthError(sink, 400, "invalid_request", "client_id is missing");
                return;
            }
            DeviceAuthorization codes = deviceAuthorizationService.start(form.get("client_id"), form.get("client_name"), baseUrl(call));
            json(sink, 200, codes);
        } else if (path.equals(JoiProtocol.TOKEN)) {
            token(form, sink);
        } else {
            oauthError(sink, 404, "invalid_request", "Not found: " + path);
        }
    }

    private void token(Map<String, String> form, ApiSink sink) throws Exception {
        String grant = StringUtils.defaultString(form.get("grant_type"));
        if (grant.equals(JoiProtocol.DEVICE_CODE_GRANT)) {
            DeviceAuthorizationService.Poll poll = deviceAuthorizationService.poll(form.get("device_code"));
            if (poll.issued() == null) {
                oauthError(sink, 400, poll.error(), null);
                return;
            }
            json(sink, 200, tokens(poll.issued()));
        } else if (grant.equals("refresh_token")) {
            AccessTokenService.Issued issued = accessTokenService.refresh(form.get("refresh_token"));
            if (issued == null) {
                oauthError(sink, 400, "invalid_grant", "The refresh token is not valid: approve the device again");
                return;
            }
            json(sink, 200, tokens(issued));
        } else {
            oauthError(sink, 400, "unsupported_grant_type", "Supported: " + JoiProtocol.DEVICE_CODE_GRANT + ", refresh_token");
        }
    }

    private static TokenResponse tokens(AccessTokenService.Issued issued) {
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds(), issued.refreshToken());
    }

    private void oauthError(ApiSink sink, int status, String error, String description) throws IOException {
        sink.respond(status, JSON, body(new OAuthError(error, description)), Map.of());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // writing
    // ----------------------------------------------------------------------------------------------------------------

    private String baseUrl(ApiCall call) {
        return configuration.getPublicUrl() != null ? configuration.getPublicUrl() : call.baseUrl();
    }

    private void send(ApiSink.EventStream events, StreamEvent event, Record data) throws IOException {
        events.send(event.eventName(), gson.toJson(data));
    }

    private void json(ApiSink sink, int status, Object value) throws IOException {
        sink.respond(status, JSON, body(value), Map.of());
    }

    private byte[] body(Object value) {
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    /** An {@link ApiError} answer. */
    static void error(ApiSink sink, int status, String code, String message) throws IOException {
        sink.respond(status, JSON, new Gson().toJson(new ApiError(code, message)).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String reason(Exception e) {
        Throwable root = ExceptionUtils.getRootCause(e);
        return StringUtils.defaultIfBlank((root == null ? e : root).getMessage(), e.getClass().getSimpleName());
    }

    private static String version() {
        String version = JoiApi.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void destroy() {
        keepAlive.shutdownNow();
    }
}
