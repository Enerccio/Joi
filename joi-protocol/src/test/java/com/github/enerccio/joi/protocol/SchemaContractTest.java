package com.github.enerccio.joi.protocol;

import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.node.*;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.LocalToken;
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
            TokenResponse.class, LocalToken.class, OAuthError.class, OAuthMetadata.class, Download.class, BodyModel.class, BodyPart.class,
            BodyToggle.class, BodyPartState.class, PartSwitch.class, PartKept.class, PartRefused.class, PlayAnimation.class, MoodState.class,
            BodyAnimation.class, BodyAnimations.class, BodyInfo.class, JoiAnimations.class, Frame.class, Hello.class, Resume.class, Welcome.class, Manifest.class,
            ToolDefinition.class, Say.class, Stop.class, Display.class, ToolUse.class, ToolCall.class, ToolProgress.class, ToolResult.class,
            ToolCancel.class, Approval.class, ServerEvent.class, TurnStart.class, ChatEntry.class, ChatHistory.class,
            ChatLog.class, TaskStep.class, TaskInfo.class, TaskList.class, TaskDenial.class, TaskApproval.class, AutoApproval.class, AutoApprovalList.class, ImageUpload.class);

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
        ProtocolSamples.http().forEach(this::valid);
    }

    @Test
    void nodeSamplesAreValid() {
        ProtocolSamples.node().forEach(this::valid);
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
        assertThat(errors(Say.class, "{\"speak\": true}")).isNotEmpty();
    }

    @Test
    void invalidMessagesAreCaught() {
        assertThat(errors(ChatRequest.class, "{\"text\": \"\", \"images\": [3]}")).isNotEmpty();
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
