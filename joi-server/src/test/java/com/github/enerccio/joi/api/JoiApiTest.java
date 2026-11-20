package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMRequest;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Joi's API without HTTP: the auth (tokens, OAuth devices, no auth), the assistants, and chat on the fake model, whole
 * and streamed. Every answer is checked against the schemas of joi-protocol.
 */
class JoiApiTest extends PlatformTestBase {

    static final String BASE = "https://github.com/Enerccio/joi/protocol/v1/";

    @Autowired
    private JoiApi api;
    @Autowired
    private ApiConfiguration configuration;
    @Autowired
    private ApiAccessService apiAccessService;
    @Autowired
    private AccessTokenService accessTokenService;
    @Autowired
    private DeviceAuthorizationService deviceAuthorizationService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private MessagePromptService messagePromptService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private TtsService ttsService;

    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    private User user;
    private Joi joi;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        user = login();
        joi = joiService.create("Kairo");
        token = accessTokenService.create("test", null).text();
    }

    @AfterEach
    void tearDown() throws Exception {
        configuration.setNoAuth(false);
        configuration.setNoAuthUser(null);
        loginAdmin();
        apiAccessService.save(ApiAccess.DEFAULT);
    }

    // ----------------------------------------------------------------------------------------------------------------

    /** What the API answered. */
    static final class Answer implements ApiSink {
        int status;
        String contentType;
        String body;
        Map<String, String> headers = Map.of();
        final List<String[]> events = new ArrayList<>();
        int comments;

        @Override
        public void respond(int status, String contentType, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = new String(body, StandardCharsets.UTF_8);
            this.headers = headers;
        }

        /** The file answered, null when none. */
        java.io.File file;

        @Override
        public void file(int status, String contentType, java.io.File file, Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.file = file;
            this.headers = headers;
        }

        @Override
        public EventStream events() {
            status = 200;
            contentType = JoiProtocol.EVENT_STREAM;
            return new EventStream() {
                @Override
                public synchronized void send(String event, String json) {
                    events.add(new String[]{event, json});
                }

                @Override
                public synchronized void comment(String text) {
                    comments++;
                }
            };
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        List<String> eventNames() {
            return events.stream().map(e -> e[0]).toList();
        }

        JsonObject event(String name) {
            return events.stream().filter(e -> e[0].equals(name)).map(e -> JsonParser.parseString(e[1]).getAsJsonObject())
                    .findFirst().orElseThrow();
        }
    }

    private Answer call(String method, String path, String authorization, String body, String... headers) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (authorization != null) {
            map.put("authorization", authorization);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            map.put(headers[i].toLowerCase(Locale.ROOT), headers[i + 1]);
        }
        Answer answer = new Answer();
        int query = path.indexOf('?');
        api.handle(new ApiCall(method, query < 0 ? path : path.substring(0, query),
                query < 0 ? Map.of() : ApiCall.parse(path.substring(query + 1)), map, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                "http://joi.test:8080"), answer);
        return answer;
    }

    private Answer get(String path) throws IOException {
        return call("GET", path, "Bearer " + token, null);
    }

    private void valid(String schema, String json) {
        assertThat(schemas.getSchema(SchemaLocation.of(BASE + schema + ".schema.json")).validate(json, InputFormat.JSON))
                .as(schema + ": " + json).isEmpty();
    }

    private void useModel() throws Exception {
        OpenAICompatible ai = createAI();
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the server and the auth
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void serverInfoNeedsNoAuthAndSaysWhatIsOffered() throws Exception {
        Answer answer = call("GET", "/api/v1", null, null);
        assertThat(answer.status).isEqualTo(200);
        valid("ServerInfo", answer.body);
        JsonObject auth = answer.json().getAsJsonObject("auth");
        assertThat(auth.getAsJsonArray("methods").asList().stream().map(JsonElement::getAsString)).containsExactly("oauth", "token");
        assertThat(auth.getAsJsonObject("oauth").get("token").getAsString()).isEqualTo("http://joi.test:8080/oauth/token");

        configuration.setNoAuth(true);
        loginAdmin();
        apiAccessService.save(new ApiAccess(true, false, 60, 90));
        Answer later = call("GET", "/api/v1/", null, null);
        valid("ServerInfo", later.body);
        assertThat(later.json().getAsJsonObject("auth").getAsJsonArray("methods").asList().stream().map(JsonElement::getAsString))
                .containsExactly("token", "none");
        assertThat(later.json().getAsJsonObject("auth").get("oauth").isJsonNull()).isTrue();
    }

    @Test
    void withoutValidCredentialsTheCallIsUnauthorized() throws Exception {
        Answer none = call("GET", "/api/v1/jois", null, null);
        assertThat(none.status).isEqualTo(401);
        assertThat(none.headers).containsKey("WWW-Authenticate");
        valid("ApiError", none.body);
        assertThat(none.json().get("code").getAsString()).isEqualTo("unauthorized");

        assertThat(call("GET", "/api/v1/jois", "Bearer joi_aaaaaaaaaaaa_nope", null).status).isEqualTo(401);
        assertThat(call("GET", "/api/v1/jois", "Bearer " + token + "x", null).status).isEqualTo(401);
        assertThat(call("GET", "/api/v1/jois", "Basic abc", null).status).isEqualTo(401);
    }

    @Test
    void aTokenActsForItsUserUntilRevokedOrTurnedOff() throws Exception {
        Answer me = get("/api/v1/me");
        assertThat(me.status).isEqualTo(200);
        valid("Me", me.body);
        assertThat(me.json().get("login").getAsString()).isEqualTo(user.getLogin());
        assertThat(me.json().get("method").getAsString()).isEqualTo("token");

        loginAdmin();
        apiAccessService.save(new ApiAccess(false, true, 60, 90));
        assertThat(get("/api/v1/me").status).isEqualTo(401);
        apiAccessService.save(ApiAccess.DEFAULT);
        assertThat(get("/api/v1/me").status).isEqualTo(200);

        loginAs(user);
        String uuid = accessTokenService.findAllForUser().getFirst().getUuid();
        assertThat(accessTokenService.revoke(uuid)).isTrue();
        assertThat(get("/api/v1/me").status).isEqualTo(401);
    }

    @Test
    void noTokensWhenTheAdministratorTurnedThemOff() throws Exception {
        loginAdmin();
        apiAccessService.save(new ApiAccess(false, true, 60, 90));
        loginAs(user);
        assertThatThrownBy(() -> accessTokenService.create("again", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noAuthActsAsTheFirstAdministratorOrTheConfiguredUser() throws Exception {
        assertThat(call("GET", "/api/v1/me", null, null).status).isEqualTo(401);
        configuration.setNoAuth(true);
        Answer me = call("GET", "/api/v1/me", null, null);
        assertThat(me.status).isEqualTo(200);
        assertThat(me.json().get("method").getAsString()).isEqualTo("none");
        User admin = userService.findByName(me.json().get("login").getAsString());
        assertThat(admin.isAdmin()).isTrue();

        configuration.setNoAuthUser(user.getLogin());
        assertThat(call("GET", "/api/v1/me", null, null).json().get("login").getAsString()).isEqualTo(user.getLogin());
        // a credential that is sent must still be valid
        assertThat(call("GET", "/api/v1/me", "Bearer joi_aaaaaaaaaaaa_nope", null).status).isEqualTo(401);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the assistants
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void listsAndDescribesOnlyTheUsersAssistants() throws Exception {
        User other = createUser();
        loginAs(other);
        Joi theirs = joiService.create("Not yours");
        loginAs(user);

        Answer list = get("/api/v1/jois");
        assertThat(list.status).isEqualTo(200);
        valid("JoiList", list.body);
        JsonArray jois = list.json().getAsJsonArray("jois");
        assertThat(jois).hasSize(1);
        assertThat(jois.get(0).getAsJsonObject().get("id").getAsString()).isEqualTo(joi.getUuid());
        assertThat(jois.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Kairo");
        assertThat(jois.get(0).getAsJsonObject().get("lastChatted").isJsonNull()).isTrue();

        Answer info = get("/api/v1/jois/" + joi.getUuid());
        assertThat(info.status).isEqualTo(200);
        valid("JoiInfo", info.body);
        assertThat(info.json().get("wakeWord").getAsString()).isEqualTo("Hey Kairo");
        assertThat(info.json().get("chatModel").isJsonNull()).isTrue();
        assertThat(info.json().get("voice").isJsonNull()).isTrue();
        JsonObject animations = info.json().getAsJsonObject("animations");
        assertThat(animations.get("sleeping").isJsonNull()).isTrue();
        assertThat(animations.getAsJsonArray("idle")).isEmpty();

        Answer notYours = get("/api/v1/jois/" + theirs.getUuid());
        assertThat(notYours.status).isEqualTo(404);
        valid("ApiError", notYours.body);
        assertThat(get("/api/v1/nothing").status).isEqualTo(404);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // chat
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void chatWithoutAModelIsAConflict() throws Exception {
        Answer answer = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\"}");
        assertThat(answer.status).isEqualTo(409);
        assertThat(answer.json().get("code").getAsString()).isEqualTo("no_model");
        assertThat(call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \" \"}").status).isEqualTo(400);
        assertThat(call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{nope").status).isEqualTo(400);
    }

    @Test
    void chatAnswersWholeStoresTheTurnAndKeepsTheHistory() throws Exception {
        useModel();
        llm.enqueue(MockLLMResponse.text("Hello, Peter.").withReasoning("He said hi."), MockLLMResponse.text("Still here."));

        Answer first = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token,
                "{\"text\": \"Hi!\", \"reasoning\": true, \"node\": \"kitchen\"}");
        assertThat(first.status).as(first.body).isEqualTo(200);
        valid("ChatReply", first.body);
        JsonObject reply = first.json();
        assertThat(reply.get("text").getAsString()).isEqualTo("Hello, Peter.");
        assertThat(reply.get("reasoning").getAsString()).isEqualTo("He said hi.");
        assertThat(reply.get("audio").isJsonNull()).isTrue();
        assertThat(reply.get("mood").getAsString()).isNotBlank();

        MockLLMRequest request = llm.lastCompletionRequest();
        String system = request.getMessages("system").getFirst();
        assertThat(system).contains("You are Kairo").contains("kitchen");

        ChatMessage stored = chatMessageService.findLatest(joi, 5).getLast();
        assertThat(stored.getUuid()).isEqualTo(reply.get("messageId").getAsString());
        assertThat(stored.getInput()).isEqualTo("Hi!");
        assertThat(stored.getResponse()).isEqualTo("Hello, Peter.");
        assertThat(stored.getResponseReasoning()).isEqualTo("He said hi.");
        assertThat(stored.getNode()).isEqualTo("kitchen");
        assertThat(messagePromptService.findByMessage(stored).getBuiltPrompt()).contains("Hi!");

        Answer second = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Still there?\"}");
        assertThat(second.json().get("text").getAsString()).isEqualTo("Still here.");
        // without asking for it, no reasoning
        assertThat(second.json().get("reasoning").isJsonNull()).isTrue();
        List<MockLLMRequest.Message> messages = llm.lastCompletionRequest().getMessages();
        assertThat(messages.stream().map(MockLLMRequest.Message::content))
                .containsSubsequence("Hi!", "Hello, Peter.", "Still there?");

        Answer listed = get("/api/v1/jois");
        assertThat(listed.json().getAsJsonArray("jois").get(0).getAsJsonObject().get("lastChatted").isJsonNull()).isFalse();
        // the mood of the day stays
        assertThat(second.json().get("mood").getAsString()).isEqualTo(reply.get("mood").getAsString());

        // her history, oldest first, paged back
        Answer history = get("/api/v1/jois/" + joi.getUuid() + "/chat");
        assertThat(history.status).as(history.body).isEqualTo(200);
        valid("ChatHistory", history.body);
        JsonArray entries = history.json().getAsJsonArray("messages");
        assertThat(entries).hasSize(2);
        JsonObject said = entries.get(0).getAsJsonObject();
        assertThat(said.get("id").getAsString()).isEqualTo(reply.get("messageId").getAsString());
        assertThat(said.get("type").getAsString()).isEqualTo("user");
        assertThat(said.get("text").getAsString()).isEqualTo("Hi!");
        assertThat(said.get("node").getAsString()).isEqualTo("kitchen");
        assertThat(said.get("reply").getAsString()).isEqualTo("Hello, Peter.");
        assertThat(entries.get(1).getAsJsonObject().get("reply").getAsString()).isEqualTo("Still here.");
        assertThat(history.json().get("more").getAsBoolean()).isFalse();

        Answer latest = get("/api/v1/jois/" + joi.getUuid() + "/chat?limit=1");
        assertThat(latest.json().getAsJsonArray("messages")).hasSize(1);
        assertThat(latest.json().get("more").getAsBoolean()).isTrue();
        String last = latest.json().getAsJsonArray("messages").get(0).getAsJsonObject().get("id").getAsString();
        Answer earlier = get("/api/v1/jois/" + joi.getUuid() + "/chat?limit=1&before=" + last);
        assertThat(earlier.json().getAsJsonArray("messages").get(0).getAsJsonObject().get("text").getAsString()).isEqualTo("Hi!");
        assertThat(earlier.json().get("more").getAsBoolean()).isFalse();
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/chat?limit=0").status).isEqualTo(400);
        assertThat(get("/api/v1/jois/" + joi.getUuid() + "/chat?before=nope").status).isEqualTo(404);
    }

    @Test
    void chatStreamsTheTextThenTheReplyThenDone() throws Exception {
        useModel();
        llm.enqueue(MockLLMResponse.chunks("Hel", "lo ", "there."));
        Answer answer = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\"}",
                "Accept", JoiProtocol.EVENT_STREAM);
        assertThat(answer.contentType).isEqualTo(JoiProtocol.EVENT_STREAM);
        List<String> names = answer.eventNames();
        assertThat(names).startsWith("text").endsWith("reply", "done").doesNotContain("error", "audio");
        StringBuilder text = new StringBuilder();
        for (String[] event : answer.events) {
            switch (event[0]) {
                case "text" -> {
                    valid("TextDelta", event[1]);
                    text.append(JsonParser.parseString(event[1]).getAsJsonObject().get("delta").getAsString());
                }
                case "reply" -> valid("ChatReply", event[1]);
                case "done" -> valid("Done", event[1]);
                default -> {
                }
            }
        }
        assertThat(text.toString()).isEqualTo("Hello there.");
        assertThat(answer.event("reply").get("text").getAsString()).isEqualTo("Hello there.");
        assertThat(answer.event("done").get("messageId").getAsString()).isEqualTo(answer.event("reply").get("messageId").getAsString());

        // "stream": true does the same
        llm.enqueue(MockLLMResponse.text("Again."));
        Answer again = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\", \"stream\": true}");
        assertThat(again.eventNames()).endsWith("reply", "done");
    }

    @Test
    void aFailingModelIsReported() throws Exception {
        useModel();
        llm.fallback(MockLLMResponse.error(500, "the model is down"));
        Answer whole = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\"}");
        assertThat(whole.status).isEqualTo(502);
        assertThat(whole.json().get("code").getAsString()).isEqualTo("model_failed");

        Answer streamed = call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\", \"stream\": true}");
        assertThat(streamed.eventNames()).endsWith("error");
        valid("ApiError", streamed.events.getLast()[1]);
        assertThat(chatMessageService.hasAny(joi)).isFalse();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // OAuth
    // ----------------------------------------------------------------------------------------------------------------

    private Answer form(String path, String body) throws IOException {
        return call("POST", path, null, body, "Content-Type", "application/x-www-form-urlencoded");
    }

    @Test
    void aDeviceGetsItsTokensOnceTheUserApproves() throws Exception {
        Answer metadata = call("GET", JoiProtocol.OAUTH_METADATA, null, null);
        valid("OAuthMetadata", metadata.body);

        Answer codes = form(JoiProtocol.DEVICE_AUTHORIZATION, "client_id=kitchen-pi&client_name=Kitchen%20Pi");
        assertThat(codes.status).isEqualTo(200);
        valid("DeviceAuthorization", codes.body);
        String deviceCode = codes.json().get("device_code").getAsString();
        String userCode = codes.json().get("user_code").getAsString();
        assertThat(userCode).matches("[A-Z]{4}-[A-Z]{4}");
        assertThat(codes.json().get("verification_uri_complete").getAsString()).isEqualTo("http://joi.test:8080/?device=" + userCode);

        Answer pending = form(JoiProtocol.TOKEN, "grant_type=" + JoiProtocol.DEVICE_CODE_GRANT + "&device_code=" + deviceCode);
        assertThat(pending.status).isEqualTo(400);
        valid("OAuthError", pending.body);
        assertThat(pending.json().get("error").getAsString()).isEqualTo("authorization_pending");
        Answer tooFast = form(JoiProtocol.TOKEN, "grant_type=" + JoiProtocol.DEVICE_CODE_GRANT + "&device_code=" + deviceCode);
        assertThat(tooFast.json().get("error").getAsString()).isEqualTo("slow_down");

        // the user types the code in any case, without the dash
        assertThat(deviceAuthorizationService.find(userCode.replace("-", "").toLowerCase(Locale.ROOT)).clientName()).isEqualTo("Kitchen Pi");
        deviceAuthorizationService.approve(userCode);

        Answer tokens = form(JoiProtocol.TOKEN, "grant_type=" + JoiProtocol.DEVICE_CODE_GRANT + "&device_code=" + deviceCode);
        assertThat(tokens.status).isEqualTo(200);
        valid("TokenResponse", tokens.body);
        String access = tokens.json().get("access_token").getAsString();
        String refresh = tokens.json().get("refresh_token").getAsString();
        assertThat(access).startsWith(AccessTokenService.ACCESS_START);
        assertThat(refresh).startsWith(AccessTokenService.REFRESH_START);
        // given once
        assertThat(form(JoiProtocol.TOKEN, "grant_type=" + JoiProtocol.DEVICE_CODE_GRANT + "&device_code=" + deviceCode)
                .json().get("error").getAsString()).isEqualTo("invalid_grant");

        Answer me = call("GET", "/api/v1/me", "Bearer " + access, null);
        assertThat(me.json().get("login").getAsString()).isEqualTo(user.getLogin());
        assertThat(me.json().get("method").getAsString()).isEqualTo("oauth");
        assertThat(accessTokenService.findAllForUser()).extracting(t -> t.getName()).contains("Kitchen Pi");

        Answer renewed = form(JoiProtocol.TOKEN, "grant_type=refresh_token&refresh_token=" + refresh);
        assertThat(renewed.status).isEqualTo(200);
        String newAccess = renewed.json().get("access_token").getAsString();
        assertThat(call("GET", "/api/v1/me", "Bearer " + newAccess, null).status).isEqualTo(200);
        assertThat(call("GET", "/api/v1/me", "Bearer " + access, null).status).isEqualTo(401);
        assertThat(form(JoiProtocol.TOKEN, "grant_type=refresh_token&refresh_token=" + refresh).json().get("error").getAsString())
                .isEqualTo("invalid_grant");
    }

    @Test
    void aDeniedDeviceIsTold() throws Exception {
        Answer codes = form(JoiProtocol.DEVICE_AUTHORIZATION, "client_id=car");
        deviceAuthorizationService.deny(codes.json().get("user_code").getAsString());
        Answer denied = form(JoiProtocol.TOKEN, "grant_type=" + JoiProtocol.DEVICE_CODE_GRANT + "&device_code="
                + codes.json().get("device_code").getAsString());
        assertThat(denied.json().get("error").getAsString()).isEqualTo("access_denied");
        assertThat(form(JoiProtocol.DEVICE_AUTHORIZATION, "client_name=nameless").json().get("error").getAsString())
                .isEqualTo("invalid_request");
        assertThat(form(JoiProtocol.TOKEN, "grant_type=password").json().get("error").getAsString()).isEqualTo("unsupported_grant_type");
    }

    @Test
    void oauthTurnedOffIsUnavailable() throws Exception {
        loginAdmin();
        apiAccessService.save(new ApiAccess(true, false, 60, 90));
        Answer answer = form(JoiProtocol.DEVICE_AUTHORIZATION, "client_id=car");
        assertThat(answer.status).isEqualTo(503);
        assertThat(answer.json().get("error").getAsString()).isEqualTo("unavailable");
        assertThat(call("GET", JoiProtocol.OAUTH_METADATA, null, null).status).isEqualTo(404);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the voice
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aNewVoiceIsAnEventWithItsDescription() throws Exception {
        Actor actor = new Actor();
        actor.setName("Hectus");
        actor.setVoice("predefined:Hectus.wav");
        actor.setDescription("low and warm, a little husky");
        ttsService.saveActor(actor);
        useModel();
        llm.reply("Hi.");
        call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "Bearer " + token, "{\"text\": \"Hi\"}");
        // the chat stored her mood of the day
        joi = joiService.find(joi.getId());

        JoiService.Persona before = joiService.persona(joi);
        joi.setVoiceActor(actor.getId());
        List<QueuedEvent> events = joiService.saveAbout(joi, before);
        assertThat(events).extracting(QueuedEvent::getKind).containsExactly(JoiEvents.VOICE_CHANGED);
        assertThat(events.getFirst().getText()).contains("Hectus").contains("low and warm, a little husky");
        assertThat(queuedEventService.findWaiting(joi)).hasSize(1);
    }
}
