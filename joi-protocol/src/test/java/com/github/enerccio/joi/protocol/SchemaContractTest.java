package com.github.enerccio.joi.protocol;

import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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
            TokenResponse.class, OAuthError.class, OAuthMetadata.class);

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
        names.add(AuthMethod.class.getSimpleName());
        for (String name : names) {
            assertThat(getClass().getResource("/joi-protocol/schemas/" + name + ".schema.json")).as(name).isNotNull();
        }
        assertThat(getClass().getResource("/joi-protocol/openapi.yaml")).isNotNull();
    }

    @Test
    void theAuthMethodsAreTheEnum() throws IOException {
        JsonObject schema = read("AuthMethod");
        List<String> values = new ArrayList<>();
        schema.getAsJsonArray("enum").forEach(v -> values.add(v.getAsString()));
        List<String> names = Arrays.stream(AuthMethod.values()).map(m -> GSON.toJson(m).replace("\"", "")).toList();
        assertThat(values).containsExactlyElementsOf(names);
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
        valid(new JoiList(List.of(new JoiSummary("2f0c", "Joi", "2026-11-18T19:00:00Z"), new JoiSummary("88aa", "Kairo", null))));
        valid(new JoiInfo("2f0c", "Joi", "Peter", new Personality("warm", "Warm"), null, "Hey Joi", null, "Gemma", new Voice("Higgs", "C", "low and warm")));
        valid(new JoiInfo("2f0c", "Joi", "Peter", null, "cheerful", "Hey Joi", null, null, null));
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
    void invalidMessagesAreCaught() {
        assertThat(errors(ChatRequest.class, "{\"text\": \"\"}")).isNotEmpty();
        assertThat(errors(ChatRequest.class, "{\"speak\": true}")).isNotEmpty();
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

    private JsonObject read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/joi-protocol/schemas/" + name + ".schema.json")) {
            assertThat(in).as(name).isNotNull();
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
