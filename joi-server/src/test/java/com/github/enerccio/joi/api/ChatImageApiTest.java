package com.github.enerccio.joi.api;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.SettingService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Images in the chat over the API, on the fake model: the upload, what the model is sent (the image itself for a model that sees, the note
 * a model that sees wrote for one that does not, the same in every later prompt), the refusal when nothing can look at it, the history and
 * the deletion with the message.
 */
class ChatImageApiTest extends PlatformTestBase {

    private static final String BASE = "https://github.com/Enerccio/joi/protocol/v1/";

    @Autowired
    private JoiApi api;
    @Autowired
    private JoiService joiService;
    @Autowired
    private AccessTokenService accessTokenService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private ChatImageService chatImageService;
    @Autowired
    private com.github.enerccio.joi.domain.service.MessagePromptService messagePromptService;
    @Autowired
    private AIService aiService;
    @Autowired
    private PersonaService personaService;
    @Autowired
    private SettingService settingService;

    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    private Joi joi;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.create("Kairo");
        token = accessTokenService.create("test", null).text();
    }

    private static final class Answer implements ApiSink {
        int status;
        String body;
        java.io.File file;
        Map<String, String> headers = Map.of();

        @Override
        public void respond(int status, String contentType, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.body = new String(body, StandardCharsets.UTF_8);
            this.headers = headers;
        }

        @Override
        public void file(int status, String contentType, java.io.File file, Map<String, String> headers) {
            this.status = status;
            this.file = file;
            this.headers = headers;
        }

        @Override
        public EventStream events() {
            return new EventStream() {
                @Override
                public void send(String event, String json) {
                }

                @Override
                public void comment(String text) {
                }
            };
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private Answer call(String method, String path, byte[] body, String... headers) throws IOException {
        Map<String, String> map = new HashMap<>();
        map.put("authorization", "Bearer " + token);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            map.put(headers[i].toLowerCase(), headers[i + 1]);
        }
        Answer answer = new Answer();
        api.handle(new ApiCall(method, path, Map.of(), map, body == null ? new byte[0] : body, "http://joi.test:8080"), answer);
        return answer;
    }

    private void valid(String schema, String json) {
        assertThat(schemas.getSchema(SchemaLocation.of(BASE + schema + ".schema.json")).validate(json, InputFormat.JSON))
                .as(schema + ": " + json).isEmpty();
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String upload(byte[] image) throws IOException {
        Answer answer = call("POST", "/api/v1/jois/" + joi.getUuid() + "/images", image);
        assertThat(answer.status).as(answer.body).isEqualTo(200);
        valid("ImageUpload", answer.body);
        return answer.json().get("id").getAsString();
    }

    private Answer chat(String text, String... images) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("text", text);
        JsonArray ids = new JsonArray();
        for (String image : images) {
            ids.add(image);
        }
        request.add("images", ids);
        return call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", request.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A chat model, multimodal or not, hers. */
    private OpenAICompatible chatModel(boolean multimodal) throws Exception {
        OpenAICompatible ai = createAI();
        ai.setMultimodal(multimodal);
        ai = (OpenAICompatible) aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        return ai;
    }

    /** The user's deep reasoning model, multimodal: it describes images for a chat model that cannot see. */
    private void describer() throws Exception {
        OpenAICompatible ai = createAI();
        ai.setMultimodal(true);
        ai = (OpenAICompatible) aiService.save(ai);
        JoiUserSetting setting = personaService.getUserSetting();
        setting.setReasoningModel(ai.getId());
        settingService.save(setting);
    }

    private static int imageParts(MockLLMRequest request) {
        return request.getRawBody().split("\"type\":\"image_url\"", -1).length - 1;
    }

    /** The data URL of the images in the request, in order. */
    private static List<String> dataUrls(MockLLMRequest request) {
        List<String> urls = new ArrayList<>();
        collect(request.getBody(), urls);
        return urls;
    }

    private static void collect(JsonElement element, List<String> urls) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("image_url") && object.get("image_url").isJsonObject()) {
                urls.add(object.getAsJsonObject("image_url").get("url").getAsString());
            }
            object.entrySet().forEach(e -> collect(e.getValue(), urls));
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> collect(e, urls));
        }
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void anUploadIsKeptAsAScaledJpegAndGivenBackWithItsIdAsTheEtag() throws Exception {
        Answer answer = call("POST", "/api/v1/jois/" + joi.getUuid() + "/images", png(3200, 2400, Color.GREEN), "Content-Type", "image/png");
        assertThat(answer.status).isEqualTo(200);
        valid("ImageUpload", answer.body);
        JsonObject kept = answer.json();
        assertThat(kept.get("mimeType").getAsString()).isEqualTo("image/jpeg");
        assertThat(kept.get("width").getAsInt()).isEqualTo(1568);
        assertThat(kept.get("height").getAsInt()).isEqualTo(1176);

        Answer got = call("GET", "/api/v1/images/" + kept.get("id").getAsString(), null);
        assertThat(got.status).isEqualTo(200);
        assertThat(got.file).isNotNull();
        assertThat(got.file.length()).isEqualTo(kept.get("size").getAsLong());
        assertThat(Files.readAllBytes(got.file.toPath())).startsWith((byte) 0xFF, (byte) 0xD8);
        String etag = got.headers.get("ETag");
        assertThat(call("GET", "/api/v1/images/" + kept.get("id").getAsString(), null, "If-None-Match", etag).status).isEqualTo(304);
        assertThat(call("GET", "/api/v1/images/nope", null).status).isEqualTo(404);
    }

    @Test
    void whatIsNoImageIsRefusedAndNeedsTheCredentials() throws Exception {
        Answer bad = call("POST", "/api/v1/jois/" + joi.getUuid() + "/images", "not an image".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "image/jpeg");
        assertThat(bad.status).isEqualTo(400);
        assertThat(bad.json().get("code").getAsString()).isEqualTo("bad_image");

        Answer anonymous = new Answer();
        api.handle(new ApiCall("POST", "/api/v1/jois/" + joi.getUuid() + "/images", Map.of(), Map.of(), new byte[]{1}, "http://joi.test"), anonymous);
        assertThat(anonymous.status).isEqualTo(401);
    }

    @Test
    void aModelThatSeesGetsTheImageAndEveryLaterPromptHasTheSameBytes() throws Exception {
        chatModel(true);
        String id = upload(png(300, 200, Color.RED));
        llm.enqueue(MockLLMResponse.text("A red picture."), MockLLMResponse.text("Still red."));

        Answer first = chat("What is this?", id);
        assertThat(first.status).as(first.body).isEqualTo(200);
        Answer second = chat("And now?");
        assertThat(second.status).isEqualTo(200);

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        assertThat(requests).hasSize(2);
        assertThat(imageParts(requests.get(0))).isEqualTo(1);
        // the message is in the history of the next prompt, with the image as it was: the model's cache keeps it
        assertThat(dataUrls(requests.get(1))).isEqualTo(dataUrls(requests.get(0))).hasSize(1);
        assertThat(dataUrls(requests.get(0)).getFirst()).startsWith("data:image/jpeg;base64,");

        // stored with the message, shown by the history, and the stored prompt names the attachment but holds no bytes
        List<ChatMessage> messages = chatMessageService.findLatest(joi, 10);
        assertThat(messages.getFirst().imageUuids()).containsExactly(id);
        assertThat(messages.getFirst().getImageNotes()).isNull();
        Answer history = call("GET", "/api/v1/jois/" + joi.getUuid() + "/chat", null);
        valid("ChatHistory", history.body);
        assertThat(history.json().getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("images").get(0).getAsString()).isEqualTo(id);
        assertThat(history.json().getAsJsonArray("messages").get(1).getAsJsonObject().get("images").isJsonNull()).isTrue();
        String prompt = messagePromptService.findByMessage(messages.getFirst()).getBuiltPrompt();
        assertThat(prompt).contains("\"attachments\"").doesNotContain("base64");
    }

    @Test
    void aMessageMayHaveOnlyAnImage() throws Exception {
        chatModel(true);
        String id = upload(png(64, 64, Color.BLUE));
        llm.enqueue(MockLLMResponse.text("Blue."));

        Answer answer = chat("", id);

        assertThat(answer.status).as(answer.body).isEqualTo(200);
        assertThat(imageParts(llm.getCompletionRequests().getFirst())).isEqualTo(1);
        assertThat(call("POST", "/api/v1/jois/" + joi.getUuid() + "/chat", "{\"text\": \"\"}".getBytes(StandardCharsets.UTF_8)).status).isEqualTo(400);
    }

    @Test
    void aModelThatCannotSeeIsGivenTheNoteAModelThatSeesWroteOnceAndTheSameNoteAfter() throws Exception {
        chatModel(false);
        describer();
        String id = upload(png(300, 200, Color.RED));
        // the description, the reply, then the reply of the next turn (no description again)
        llm.enqueue(MockLLMResponse.text("A plain red rectangle."), MockLLMResponse.text("It is red."), MockLLMResponse.text("Yes, red."));

        assertThat(chat("What colour is it?", id).status).isEqualTo(200);
        assertThat(chat("Sure?").status).isEqualTo(200);

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        assertThat(requests).hasSize(3);
        assertThat(imageParts(requests.get(0))).isEqualTo(1);
        assertThat(requests.get(0).getRawBody()).contains("What colour is it?");
        // the chat model sees no image, only what was written about it, after the words
        assertThat(imageParts(requests.get(1))).isZero();
        String chatPrompt = requests.get(1).getRawBody();
        assertThat(chatPrompt).contains("What colour is it?").contains("A plain red rectangle.");
        assertThat(chatPrompt.indexOf("What colour is it?")).isLessThan(chatPrompt.indexOf("A plain red rectangle."));
        // next turn: the same note, no new description
        assertThat(imageParts(requests.get(2))).isZero();
        assertThat(requests.get(2).getRawBody()).contains("A plain red rectangle.");
        assertThat(chatMessageService.findLatest(joi, 10).getFirst().imageNoteTexts()).containsExactly("A plain red rectangle.");
    }

    @Test
    void whenNothingCanLookAtTheImageTheUserIsToldAndNothingIsStored() throws Exception {
        chatModel(false);
        String id = upload(png(32, 32, Color.RED));

        Answer answer = chat("Look", id);

        assertThat(answer.status).isEqualTo(409);
        assertThat(answer.json().get("code").getAsString()).isEqualTo("no_vision");
        assertThat(chatMessageService.findLatest(joi, 10)).isEmpty();
        assertThat(llm.getCompletionRequests()).isEmpty();
        // and her info says so, for a client to hide its attach button
        Answer info = call("GET", "/api/v1/jois/" + joi.getUuid(), null);
        valid("JoiInfo", info.body);
        assertThat(info.json().get("images").getAsBoolean()).isFalse();
        describer();
        assertThat(call("GET", "/api/v1/jois/" + joi.getUuid(), null).json().get("images").getAsBoolean()).isTrue();
    }

    @Test
    void anImageGoesWithOneMessageOnly() throws Exception {
        chatModel(true);
        String id = upload(png(32, 32, Color.RED));
        llm.enqueue(MockLLMResponse.text("Red."));
        assertThat(chat("Look", id).status).isEqualTo(200);

        Answer again = chat("And again", id);

        assertThat(again.status).isEqualTo(400);
        assertThat(chat("Nothing", "no-such-image").status).isEqualTo(400);
        String a = upload(png(8, 8, Color.RED));
        assertThat(chat("Too many", a, upload(png(8, 8, Color.RED)), upload(png(8, 8, Color.RED)), upload(png(8, 8, Color.RED)),
                upload(png(8, 8, Color.RED))).status).isEqualTo(400);
    }

    @Test
    void deletingTheMessageDeletesItsImage() throws Exception {
        chatModel(true);
        String id = upload(png(32, 32, Color.RED));
        llm.enqueue(MockLLMResponse.text("Red."));
        assertThat(chat("Look", id).status).isEqualTo(200);
        assertThat(chatImageService.find(id)).isNotNull();
        java.io.File file = chatImageService.file(id);
        assertThat(file).isNotNull();

        ChatMessage message = chatMessageService.findLatest(joi, 10).getFirst();
        chatMessageService.deleteMessages(joi, List.of(message.getId()));

        assertThat(chatImageService.find(id)).isNull();
        assertThat(call("GET", "/api/v1/images/" + id, null).status).isEqualTo(404);
    }
}
