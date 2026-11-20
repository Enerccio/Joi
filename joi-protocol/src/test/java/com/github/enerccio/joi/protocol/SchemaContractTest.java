package com.github.enerccio.joi.protocol;

import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.node.*;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.networknt.schema.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schemas are the source of truth: every record has its schema with the same properties, and what the records
 * serialize to (with Gson, as the server does) is valid by the schemas.
 */
class SchemaContractTest {

    static final String BASE = "https://github.com/Enerccio/joi/protocol/v1/";

    static final List<Class<? extends Record>> RECORDS = List.of(ServerInfo.class, AuthInfo.class, OAuthEndpoints.class, Me.class,
            JoiSummary.class, JoiList.class, Personality.class, Voice.class, JoiInfo.class, ChatRequest.class, Audio.class, Usage.class,
            ChatReply.class, TextDelta.class, AudioChunk.class, Done.class, ApiError.class, DeviceAuthorization.class,
            TokenResponse.class, OAuthError.class, OAuthMetadata.class, Download.class, BodyModel.class, BodyPart.class,
            BodyToggle.class, BodyPartState.class, PartSwitch.class, PartKept.class, PartRefused.class, PlayAnimation.class,
            BodyAnimation.class, BodyAnimations.class, BodyInfo.class, JoiAnimations.class, Frame.class, Hello.class, Resume.class, Welcome.class, Manifest.class,
            ToolDefinition.class, Say.class, Stop.class, Display.class, ToolUse.class, ToolCall.class, ToolProgress.class, ToolResult.class,
            ToolCancel.class, Approval.class, ServerEvent.class, TurnStart.class, ChatEntry.class, ChatHistory.class,
            TaskStep.class, TaskInfo.class, TaskList.class, TaskDenial.class);

    /** The enums with a schema of their own: the values are the schema's enum. */
    static final List<Class<? extends Enum<?>>> ENUMS = List.of(AuthMethod.class, NodePart.class, ToolTier.class,
            ApprovalState.class);

    /** As the server writes them: nulls included, so that "null" and "missing" are both checked against the schemas. */
    static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    @Test
    void everyRecordHasItsSchemaWithTheSameProperties() throws IOException {
        for (Class<? extends Record> type : RECORDS) {
            JsonObject schema = read(type.getSimpleName());
            assertThat(schema.get("title").getAsString()).isEqualTo(type.getSimpleName());
            Set<String> properties = schema.getAsJsonObject("properties").keySet();
            assertThat(properties).as(type.getSimpleName()).containsExactlyInAnyOrderElementsOf(jsonNames(type));
            schema.getAsJsonArray("required").forEach(r -> assertThat(properties).as(type.getSimpleName()).contains(r.getAsString()));
        }
    }

    @Test
    void everySchemaHasItsType() throws IOException {
        Set<String> names = new HashSet<>();
        RECORDS.forEach(r -> names.add(r.getSimpleName()));
        ENUMS.forEach(e -> names.add(e.getSimpleName()));
        names.add(NodeMessage.class.getSimpleName());
        for (String name : names) {
            assertThat(getClass().getResource("/joi-protocol/schemas/" + name + ".schema.json")).as(name).isNotNull();
        }
        assertThat(getClass().getResource("/joi-protocol/openapi.yaml")).isNotNull();
        assertThat(getClass().getResource("/joi-protocol/asyncapi.yaml")).isNotNull();
    }

    @Test
    void theEnumsAreTheirSchemas() throws IOException {
        for (Class<? extends Enum<?>> type : ENUMS) {
            JsonObject schema = read(type.getSimpleName());
            List<String> names = Arrays.stream(type.getEnumConstants()).map(m -> GSON.toJson(m).replace("\"", "")).toList();
            assertThat(values(schema.getAsJsonArray("enum"))).as(type.getSimpleName()).containsExactlyElementsOf(names);
        }
    }

    @Test
    void theNodeMessagesAreTheirSchemaAndTheAsyncApi() throws IOException {
        List<String> types = Arrays.stream(NodeMessage.values()).map(NodeMessage::type).toList();
        assertThat(values(read("NodeMessage").getAsJsonArray("enum"))).containsExactlyInAnyOrderElementsOf(types);
        String asyncApi;
        try (InputStream in = getClass().getResourceAsStream("/joi-protocol/asyncapi.yaml")) {
            asyncApi = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // what each side sends: the messages of the two operations
        String sends = asyncApi.substring(asyncApi.indexOf("  nodeSends:"), asyncApi.indexOf("  nodeReceives:"));
        String receives = asyncApi.substring(asyncApi.indexOf("  nodeReceives:"), asyncApi.indexOf("\ncomponents:"));
        for (NodeMessage message : NodeMessage.values()) {
            assertThat(NodeMessage.of(message.type())).isEqualTo(message);
            if (message.dataClass() != null) {
                assertThat(RECORDS).contains(message.dataClass());
            }
            String channel = "\n      " + message.type() + ": { $ref: \"#/components/messages/" + message.type() + "\" }";
            assertThat(asyncApi).as(message.type()).contains(channel);
            String operation = "/messages/" + message.type() + "\" }";
            boolean toServer = message.direction() != NodeMessage.Direction.TO_NODE;
            boolean toNode = message.direction() != NodeMessage.Direction.TO_SERVER;
            assertThat(sends.contains(operation)).as(message.type() + " sent by the node").isEqualTo(toServer);
            assertThat(receives.contains(operation)).as(message.type() + " sent by the server").isEqualTo(toNode);
        }
        assertThat(NodeMessage.of("hologram")).isNull();
    }

    @Test
    void theStreamEventsHaveTheirSchemas() {
        for (StreamEvent event : StreamEvent.values()) {
            assertThat(RECORDS).contains(event.dataClass());
            assertThat(StreamEvent.of(event.eventName())).isEqualTo(event);
        }
    }

    @Test
    void samplesAreValid() {
        Usage usage = new Usage(812, 64, 900, 4100, 7300);
        Audio audio = new Audio("audio/mpeg", "SUQz");
        valid(new ServerInfo("1.0", "joi-server", "0.1.0",
                new AuthInfo(List.of(AuthMethod.TOKEN, AuthMethod.OAUTH),
                        new OAuthEndpoints("http://h/oauth/device_authorization", "http://h/oauth/token", "http://h/?device="))));
        valid(new ServerInfo("1.0", "joi-server", "0.1.0", new AuthInfo(List.of(AuthMethod.NONE), null)));
        valid(new Me("peter", "Peter", AuthMethod.NONE));
        valid(new ChatHistory(List.of(new ChatEntry("m1", "2026-11-18T19:00:00Z", "event", null, "node.first_boot", "desk", "I'm awake."),
                new ChatEntry("m2", "2026-11-18T19:01:00Z", "user", "Hi", null, null, null)), true));
        valid(new TaskList(List.of(new TaskInfo("t1", "Weather in Prague", "web_search", "waiting", "What is the weather in Prague?", null,
                null, null, "2026-11-20T10:00:00Z", null, null, null))));
        valid(new TaskInfo("t2", "Read the page", "fetch_page", "done", "What does it say?", "https://example.com/", "It says hello.", null,
                "2026-11-20T10:00:00Z", "2026-11-20T10:01:00Z", "2026-11-20T10:02:00Z",
                List.of(new TaskStep("system", "Find it.", null), new TaskStep("assistant", null, "[{\"name\":\"read_page\"}]"),
                        new TaskStep("tool", "Hello", null))));
        valid(new TaskDenial(null));
        valid(new TaskDenial("Not now"));
        valid(new JoiList(List.of(new JoiSummary("2f0c", "Joi", "2026-11-18T19:00:00Z"), new JoiSummary("88aa", "Kairo", null))));
        valid(new JoiInfo("2f0c", "Joi", "Peter", new Personality("warm", "Warm"), null, "Hey Joi", null, "Gemma", new Voice("Higgs", "C", "low and warm"),
                new JoiAnimations("p1", null, "p2", List.of("p3", "p4"), List.of())));
        valid(new JoiInfo("2f0c", "Joi", "Peter", null, "cheerful", "Hey Joi", null, null, null, null));
        Download joim = new Download("http://h/api/v1/models/m1/joim", "a".repeat(64), 120_000_000, "2026-11-19T12:00:00Z", true);
        valid(new BodyInfo("b1", "Street", new BodyModel("m1", "Ryuu", joim), null,
                List.of(new BodyPart("group:eyes", "eyes", "bare", "bare", true, List.of(new BodyPartState("bare", "bare", true,
                        List.of(new BodyToggle("Band", null, false, false))))), new BodyPart("t1", "halo", null, null, false, null)),
                List.of(new BodyToggle("Band", null, false, true), new BodyToggle("Body", "Hide Halo", true, true)),
                "Silver hair.", new BodyAnimations(false, List.of(new BodyAnimation("a1", "Wave", null, true)),
                new Download("http://h/api/v1/bodies/b1/joia", "b".repeat(64), 5000, "2026-11-19T12:00:00Z", false))));
        valid(new BodyInfo("b1", null, new BodyModel("m1", null, null), null, List.of(), null, null, new BodyAnimations(true, List.of(), null)));
        valid(new ChatRequest("Hi!", null, null, null, null));
        valid(new ChatRequest("Hi!", false, true, true, "kitchen"));
        valid(new ChatReply("m1", "Hello.", null, "cheerful", "Gemma", audio, null, usage));
        valid(new ChatReply("m1", "Hello.", "hm", null, null, null, "The speech server is down", usage));
        valid(new TextDelta("Hel"));
        valid(new AudioChunk(0, "Hello.", "audio/wav", "UklG"));
        valid(new Done("m1", null));
        valid(new ApiError("not_found", "No such assistant"));
        valid(new DeviceAuthorization("dc", "WDJB-MJHT", "http://h/?device", "http://h/?device=WDJB-MJHT", 600, 5));
        valid(new TokenResponse("joia_x", "Bearer", 3600, "joir_y"));
        valid(new OAuthError("authorization_pending", null));
        valid(new OAuthMetadata("http://h", "http://h/oauth/device_authorization", "http://h/oauth/token",
                List.of(JoiProtocol.DEVICE_CODE_GRANT, "refresh_token"), List.of("none")));
    }

    @Test
    void nodeSamplesAreValid() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        JsonObject properties = new JsonObject();
        properties.add("path", path);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", "~/notes.md");

        Hello hello = new Hello(JoiProtocol.NODE_VERSION, "6f1d-4c2a", "desk", "desktop", "joi-node 0.1.0", "2f0c",
                List.of(NodePart.TOOLS, NodePart.AUDIO_OUT, NodePart.APPROVALS), null, null);
        valid(hello);
        valid(new Hello("1.3", "n1", "kitchen-pi", null, null, "2f0c", List.of(), "joi_x", true));
        valid(new Welcome("1.2", "joi-server", "0.1.0", "s1", new JoiSummary("2f0c", "Joi", null), 30));
        valid(new Welcome("1.5", "joi-server", "0.1.0", "s1", new JoiSummary("2f0c", "Joi", null), 30, "2026-11-20T10:00:00Z"));
        valid(new Hello("1.5", "n1", "desk", "desktop", null, "2f0c", List.of(), null, true,
                new Resume("2026-11-20T10:00:00Z", "2026-11-20T10:05:00Z", 1006)));
        valid(new Resume(null, null, null));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("microphone", true);
        Manifest manifest = new Manifest(3, "macos 26.1", "aarch64", capabilities,
                List.of(new ToolDefinition("read_file", "Reads a text file", parameters, ToolTier.ALLOW),
                        new ToolDefinition("shell", "Runs a command", parameters, ToolTier.ASK)));
        valid(manifest);
        valid(new Manifest(0, null, null, null, List.of()));
        valid(new Say("What's on my calendar?", null, null));
        valid(new Say("Hi", false, true));
        valid(new Say("Hi", null, null, true));
        valid(new Stop(null));
        valid(new Stop("t1"));
        valid(new Display(true));
        valid(new PartSwitch("group:eyes", "bare"));
        valid(new PartKept("group:eyes", "bare", true));
        valid(new PartRefused("group:eyes", "bare", "Not in front of the guests."));
        valid(new PartRefused("group:eyes", "bare", null));
        valid(new PlayAnimation("5b1e0c4e-0000-4000-8000-000000000001", "wave", null));
        valid(new PlayAnimation("5b1e0c4e-0000-4000-8000-000000000001", "dance", true));
        valid(new ToolUse("desk__read_file", "{\"path\":\"~/notes.md\"}", "milk, eggs", true));
        valid(new ToolUse("desk__shell", "{}", null, false));
        valid(new ToolCall("c1", "read_file", arguments, "t1", 60));
        valid(new ToolCall("c2", "shell", new JsonObject(), null, 60));
        valid(new ToolProgress("c1", "Reading"));
        valid(new ToolResult("c1", true, "milk, eggs", null));
        valid(new ToolResult("c2", false, "The user said no", "denied"));
        valid(new ToolCancel("c2", null));
        valid(new Approval("c2", ApprovalState.ASKED, null, null));
        valid(new Approval("c2", ApprovalState.ALLOWED, "session", "voice"));
        valid(new ServerEvent("body", "2026-11-19T12:00:00Z"));
        valid(new TurnStart("s1", "first_boot"));

        valid(new Frame("hello", null, null, GSON.toJsonTree(hello)));
        valid(new Frame("say", "t1", null, GSON.toJsonTree(new Say("Hi", null, null))));
        valid(new Frame("text", null, "t1", GSON.toJsonTree(new TextDelta("Hel"))));
        valid(new Frame("ping", "p1", null, null));
        valid(new Frame("pong", null, "p1", null));
    }

    @Test
    void invalidNodeMessagesAreCaught() {
        assertThat(errors(Frame.class, "{\"id\": \"t1\"}")).isNotEmpty();
        assertThat(errors(Frame.class, "{\"type\": \"Say!\"}")).isNotEmpty();
        assertThat(errors(Frame.class, "{\"type\": \"say\", \"id\": \"has space\"}")).isNotEmpty();
        assertThat(errors(Hello.class, "{\"protocol\": \"1.2\", \"nodeId\": \"n\", \"name\": \"My Desk\", \"joi\": \"j\", \"parts\": []}"))
                .isNotEmpty();
        assertThat(errors(Hello.class, "{\"protocol\": \"1.2\", \"nodeId\": \"n\", \"name\": \"desk\", \"joi\": \"j\", \"parts\": [\"telepathy\"]}"))
                .isNotEmpty();
        assertThat(errors(ToolDefinition.class, "{\"name\": \"desk.shell\", \"description\": \"d\", \"parameters\": {}, \"tier\": \"ask\"}"))
                .isNotEmpty();
        assertThat(errors(ToolDefinition.class, "{\"name\": \"shell\", \"description\": \"d\", \"parameters\": {}, \"tier\": \"maybe\"}"))
                .isNotEmpty();
        assertThat(errors(ToolResult.class, "{\"callId\": \"c1\", \"ok\": true}")).isNotEmpty();
        assertThat(errors(Say.class, "{\"text\": \"\"}")).isNotEmpty();
    }

    @Test
    void invalidMessagesAreCaught() {
        assertThat(errors(ChatRequest.class, "{\"text\": \"\"}")).isNotEmpty();
        assertThat(errors(ChatRequest.class, "{\"speak\": true}")).isNotEmpty();
        assertThat(errors(Download.class, "{\"url\": \"u\", \"sha256\": \"x\", \"size\": 1, \"writtenAt\": \"2026-11-19T12:00:00Z\", \"current\": true}"))
                .isNotEmpty();
        assertThat(errors(ServerInfo.class, "{\"protocol\": \"one\", \"server\": \"s\", \"version\": \"v\", \"auth\": {\"methods\": [\"magic\"]}}"))
                .isNotEmpty();
    }

    private void valid(Record message) {
        assertThat(errors(message.getClass(), GSON.toJson(message))).as(message.toString()).isEmpty();
    }

    private Set<ValidationMessage> errors(Class<?> type, String json) {
        JsonSchema schema = factory.getSchema(SchemaLocation.of(BASE + type.getSimpleName() + ".schema.json"));
        return schema.validate(json, InputFormat.JSON);
    }

    private static List<String> jsonNames(Class<? extends Record> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            SerializedName name = field(type, component.getName()).getAnnotation(SerializedName.class);
            names.add(name != null ? name.value() : component.getName());
        }
        return names;
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> values(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.getAsString()));
        return values;
    }

    private JsonObject read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/joi-protocol/schemas/" + name + ".schema.json")) {
            assertThat(in).as(name).isNotNull();
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
