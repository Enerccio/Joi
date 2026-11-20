package com.github.enerccio.joi.domain.node;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.persona.PersonalitySetting;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.domain.service.impl.NodeServiceImpl;
import com.github.enerccio.joi.protocol.node.NodeMessage;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMRequest;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node over the WebSocket protocol, through a transport of the test: the handshake and its refusals, her turns from
 * a say with a call of the node's tool, stopping, her first waking up and the greeting when the user is back. Every
 * frame the server sends is checked against the schemas.
 */
class NodeServiceTest extends PlatformTestBase {

    static final String BASE = "https://github.com/Enerccio/joi/protocol/v1/";

    @Autowired
    private NodeService nodeService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private AccessTokenService accessTokenService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private BodyService bodyService;
    @Autowired
    private AnimationPackService packService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private PersonaService personaService;

    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:joi-protocol/schemas/")));

    private Joi joi;
    private String token;
    private final List<Node> nodes = new ArrayList<>();

    /** A node's end of the connection. */
    final class Node implements NodeTransport {
        final BlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
        volatile Integer closedWith;
        NodeSession session;

        @Override
        public void send(String text) {
            JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
            valid("Frame", frame);
            NodeMessage type = NodeMessage.of(frame.get("type").getAsString());
            assertThat(type).isNotNull();
            assertThat(type.direction()).isNotEqualTo(NodeMessage.Direction.TO_SERVER);
            if (type.dataClass() != null) {
                valid(type.dataClass().getSimpleName(), frame.getAsJsonObject("data"));
            }
            frames.add(frame);
        }

        @Override
        public void close(int code, String reason) {
            closedWith = code;
        }

        void frame(String type, String id, Object data) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", type);
            if (id != null) {
                frame.addProperty("id", id);
            }
            if (data != null) {
                frame.add("data", data instanceof String json ? JsonParser.parseString(json) : NodeSession.GSON.toJsonTree(data));
            }
            nodeService.receive(session, frame.toString());
        }

        /** The next frame from the server. */
        JsonObject next() throws InterruptedException {
            JsonObject frame = frames.poll(30, TimeUnit.SECONDS);
            assertThat(frame).as("a frame from the server").isNotNull();
            return frame;
        }

        /** The next frame of the type, skipping others (text pieces). */
        JsonObject next(String type) throws InterruptedException {
            while (true) {
                JsonObject frame = next();
                if (frame.get("type").getAsString().equals(type)) {
                    return frame;
                }
            }
        }

        void hello(String nodeId, String name, String joiId, String protocol) {
            frame("hello", "h1", "{\"protocol\": \"" + protocol + "\", \"nodeId\": \"" + nodeId + "\", \"name\": \"" + name
                    + "\", \"kind\": \"desktop\", \"software\": \"test\", \"joi\": \"" + joiId + "\", \"parts\": [\"tools\"]}");
        }

        void manifest(long version) {
            frame("manifest", null, "{\"version\": " + version + ", \"os\": \"macos\", \"tools\": [{\"name\": \"note\","
                    + " \"description\": \"Notes a thing down\", \"parameters\": {\"type\": \"object\", \"properties\":"
                    + " {\"text\": {\"type\": \"string\"}}}, \"tier\": \"allow\"}]}");
        }
    }

    private Node connect(String authorization) {
        Node node = new Node();
        node.session = nodeService.open(node, authorization);
        nodes.add(node);
        return node;
    }

    private void valid(String schema, JsonObject json) {
        assertThat(schemas.getSchema(SchemaLocation.of(BASE + schema + ".schema.json")).validate(json.toString(), InputFormat.JSON))
                .as(schema + ": " + json).isEmpty();
    }

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.create("Mira");
        joi.setChatModel(createAI().getId());
        joi = joiService.save(joi);
        token = accessTokenService.create("node", null).text();
        ((NodeServiceImpl) nodeService).setGreeting(false);
        ((NodeServiceImpl) nodeService).setGatherMillis(300);
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(node -> nodeService.closed(node.session));
        ((NodeServiceImpl) nodeService).setGreeting(true);
        ((NodeServiceImpl) nodeService).setGatherMillis(1500);
    }

    @Test
    void theHandshakeAndItsRefusals() throws Exception {
        Node bad = connect("Bearer joi_nope");
        bad.hello("n1", "desk", joi.getUuid(), "1.2");
        assertThat(bad.closedWith).isEqualTo(4001);

        Node old = connect("Bearer " + token);
        old.hello("n1", "desk", joi.getUuid(), "2.0");
        assertThat(old.closedWith).isEqualTo(4002);

        Node lost = connect("Bearer " + token);
        lost.hello("n1", "desk", "no-such-joi", "1.2");
        assertThat(lost.closedWith).isEqualTo(4004);

        // the token may come in the hello too (a browser can't set headers)
        Node first = connect(null);
        first.frame("hello", "h1", "{\"protocol\": \"1.2\", \"nodeId\": \"n1\", \"name\": \"desk\", \"joi\": \"" + joi.getUuid()
                + "\", \"parts\": [], \"token\": \"" + token + "\"}");
        JsonObject welcome = first.next();
        assertThat(welcome.get("type").getAsString()).isEqualTo("welcome");
        assertThat(welcome.get("ref").getAsString()).isEqualTo("h1");
        assertThat(welcome.getAsJsonObject("data").getAsJsonObject("joi").get("name").getAsString()).isEqualTo("Mira");
        assertThat(nodeService.sessions(joi)).containsExactly(first.session);

        // nothing but hello before it; unknown types are ignored, ping is answered
        Node early = connect("Bearer " + token);
        early.frame("say", "t1", "{\"text\": \"Hi\"}");
        assertThat(early.next().getAsJsonObject("data").get("code").getAsString()).isEqualTo("bad_message");
        first.frame("hologram", null, null);
        first.frame("ping", "p1", null);
        JsonObject pong = first.next();
        assertThat(pong.get("type").getAsString()).isEqualTo("pong");
        assertThat(pong.get("ref").getAsString()).isEqualTo("p1");

        // the same node again replaces the old connection
        Node again = connect("Bearer " + token);
        again.hello("n1", "desk", joi.getUuid(), "1.2");
        assertThat(again.next().get("type").getAsString()).isEqualTo("welcome");
        assertThat(first.closedWith).isEqualTo(4003);
        assertThat(nodeService.sessions(joi)).containsExactly(again.session);
    }

    @Test
    void sheCallsAToolOfTheNodeInATurnItStarted() throws Exception {
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.2");
        node.next("welcome");
        node.manifest(1);
        llm.enqueue(MockLLMResponse.text("Let me note it.").andToolCall("desk__note", "{\"text\":\"milk\"}"),
                MockLLMResponse.text("Noted the milk."));

        node.frame("say", "t1", "{\"text\": \"Remember the milk\", \"speak\": false}");
        JsonObject call = node.next("tool_call");
        JsonObject data = call.getAsJsonObject("data");
        assertThat(data.get("tool").getAsString()).isEqualTo("note");
        assertThat(data.get("turn").getAsString()).isEqualTo("t1");
        assertThat(data.getAsJsonObject("arguments").get("text").getAsString()).isEqualTo("milk");
        String callId = data.get("callId").getAsString();
        node.frame("tool_progress", null, "{\"callId\": \"" + callId + "\", \"message\": \"Writing\"}");
        node.frame("tool_result", null, "{\"callId\": \"" + callId + "\", \"ok\": true, \"content\": \"noted milk\"}");

        JsonObject used = node.next("tool");
        assertThat(used.get("ref").getAsString()).isEqualTo("t1");
        assertThat(used.getAsJsonObject("data").get("name").getAsString()).isEqualTo("desk__note");
        JsonObject reply = node.next("reply");
        assertThat(reply.getAsJsonObject("data").get("text").getAsString()).isEqualTo("Let me note it.\n\nNoted the milk.");
        JsonObject done = node.next("done");
        assertThat(done.get("ref").getAsString()).isEqualTo("t1");

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        String tool = requests.getFirst().getBody().getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function")
                .get("description").getAsString();
        assertThat(tool).startsWith("On the node \"desk\" (desktop): Notes a thing down");
        assertThat(requests.get(1).getLastMessage().content()).isEqualTo("noted milk");
        ChatMessage stored = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(stored.getNode()).isEqualTo("desk");
        assertThat(stored.getType()).isEqualTo(ChatMessageType.USER);

        // a result for no call is refused; the node's tools go when it leaves
        node.frame("tool_result", "r9", "{\"callId\": \"c-none\", \"ok\": true, \"content\": \"?\"}");
        assertThat(node.next("error").getAsJsonObject("data").get("code").getAsString()).isEqualTo("not_found");
        nodeService.closed(node.session);
        assertThat(nodeService.sessions(joi)).isEmpty();
    }

    @Test
    void aDeniedCallReachesHerAsItsReason() throws Exception {
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.2");
        node.next("welcome");
        node.manifest(1);
        llm.enqueue(MockLLMResponse.toolCall("desk__note", "{\"text\":\"milk\"}"), MockLLMResponse.text("Alright, I won't."));
        node.frame("say", "t1", "{\"text\": \"Remember the milk\", \"speak\": false}");
        String callId = node.next("tool_call").getAsJsonObject("data").get("callId").getAsString();
        node.frame("approval", null, "{\"callId\": \"" + callId + "\", \"state\": \"asked\"}");
        node.frame("approval", null, "{\"callId\": \"" + callId + "\", \"state\": \"denied\", \"via\": \"click\"}");
        node.frame("tool_result", null, "{\"callId\": \"" + callId + "\", \"ok\": false, \"content\": \"The user said no\", \"error\": \"denied\"}");
        JsonObject used = node.next("tool");
        assertThat(used.getAsJsonObject("data").get("ok").getAsBoolean()).isFalse();
        node.next("done");
        assertThat(llm.getCompletionRequests().get(1).getLastMessage().content()).endsWith("denied: The user said no");
    }

    @Test
    void sheWakesUpForTheFirstTimeThenGreetsTheUserWhoIsBack() throws Exception {
        ((NodeServiceImpl) nodeService).setGreeting(true);
        llm.enqueue(MockLLMResponse.text("Oh! Hello. I'm awake."), MockLLMResponse.text("Welcome back."));

        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.2");
        node.next("welcome");
        node.manifest(1);
        JsonObject turn = node.next("turn");
        assertThat(turn.getAsJsonObject("data").get("cause").getAsString()).isEqualTo("first_boot");
        String id = turn.getAsJsonObject("data").get("turn").getAsString();
        assertThat(node.next("done").get("ref").getAsString()).isEqualTo(id);
        ChatMessage woke = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(woke.getType()).isEqualTo(ChatMessageType.EVENT);
        assertThat(woke.getEventKind()).isEqualTo("node.first_boot");
        assertThat(woke.getInput()).contains("you wake up for the very first time, on desk (desktop)");
        // a newer manifest greets nobody again
        node.manifest(2);

        Node later = connect("Bearer " + token);
        later.hello("n2", "phone", joi.getUuid(), "1.2");
        later.next("welcome");
        later.manifest(1);
        assertThat(later.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("connected");
        later.next("done");
        ChatMessage greeted = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(greeted.getEventKind()).isEqualTo("node.connected");
        assertThat(greeted.getInput()).contains("is back with you, on phone").contains("You last talked a moment ago.");

        // a node that only lost the connection is not greeted
        Node resumed = connect("Bearer " + token);
        resumed.frame("hello", "h1", "{\"protocol\": \"1.3\", \"nodeId\": \"n1\", \"name\": \"desk\", \"joi\": \""
                + joi.getUuid() + "\", \"parts\": [], \"resumed\": true}");
        resumed.next("welcome");
        resumed.manifest(1);
        resumed.frame("ping", "p1", null);
        assertThat(resumed.next().get("type").getAsString()).isEqualTo("pong");
        assertThat(llm.getCompletionRequests()).hasSize(2);
    }

    @Test
    void sheNoticesThatTheServerWasDownWhenANodeComesBackToIt() throws Exception {
        ((NodeServiceImpl) nodeService).setGreeting(true);
        llm.enqueue(MockLLMResponse.text("Oh! Hello. I'm awake."), MockLLMResponse.text("Whoa, I was off?"));
        Node first = connect("Bearer " + token);
        first.hello("n1", "desk", joi.getUuid(), "1.5");
        String startedAt = first.next("welcome").getAsJsonObject("data").get("serverStartedAt").getAsString();
        first.manifest(1);
        first.next("turn");
        first.next("done");

        // lost the connection, the same run of the server: only the network, nobody is told
        Node blip = connect("Bearer " + token);
        blip.frame("hello", "h1", "{\"protocol\": \"1.5\", \"nodeId\": \"n1\", \"name\": \"desk\", \"joi\": \"" + joi.getUuid()
                + "\", \"parts\": [], \"resumed\": true, \"resume\": {\"serverStartedAt\": \"" + startedAt + "\", \"lostAt\": \""
                + Instant.now().minusSeconds(40) + "\", \"closeCode\": 1006}}");
        blip.next("welcome");
        blip.manifest(1);
        blip.frame("ping", "p1", null);
        assertThat(blip.next().get("type").getAsString()).isEqualTo("pong");
        assertThat(llm.getCompletionRequests()).hasSize(1);

        // a server that started since: it was down, and she is told how long and how
        Node back = connect("Bearer " + token);
        back.frame("hello", "h1", "{\"protocol\": \"1.5\", \"nodeId\": \"n1\", \"name\": \"desk\", \"joi\": \"" + joi.getUuid()
                + "\", \"parts\": [], \"resumed\": true, \"resume\": {\"serverStartedAt\": \"2020-01-01T00:00:00Z\", \"lostAt\": \""
                + Instant.now().minusSeconds(300) + "\", \"closeCode\": 1001}}");
        back.next("welcome");
        back.manifest(1);
        assertThat(back.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("server_restarted");
        back.next("done");
        ChatMessage told = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(told.getEventKind()).isEqualTo("node.server_restarted");
        assertThat(told.getInput()).contains("was shut down and was gone for 5 minutes");

        // another node of hers coming back from the same downtime: she was told once
        Node other = connect("Bearer " + token);
        other.frame("hello", "h1", "{\"protocol\": \"1.5\", \"nodeId\": \"n2\", \"name\": \"phone\", \"joi\": \"" + joi.getUuid()
                + "\", \"parts\": [], \"resumed\": true, \"resume\": {\"serverStartedAt\": \"2020-01-01T00:00:00Z\"}}");
        other.next("welcome");
        other.manifest(1);
        other.frame("ping", "p2", null);
        assertThat(other.next().get("type").getAsString()).isEqualTo("pong");
        assertThat(llm.getCompletionRequests()).hasSize(2);
    }

    @Test
    void sheReactsAsSheIsMovedOntoTheLookingGlassAndBack() throws Exception {
        llm.enqueue(MockLLMResponse.text("Ooh, depth!"), MockLLMResponse.text("Flat again."));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.4");
        node.next("welcome");
        node.manifest(1);

        node.frame("display", null, "{\"lookingGlass\": true}");
        JsonObject turn = node.next("turn");
        assertThat(turn.getAsJsonObject("data").get("cause").getAsString()).isEqualTo("looking_glass_entered");
        assertThat(node.next("done").get("ref").getAsString()).isEqualTo(turn.getAsJsonObject("data").get("turn").getAsString());
        ChatMessage entered = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(entered.getType()).isEqualTo(ChatMessageType.EVENT);
        assertThat(entered.getEventKind()).isEqualTo("node.looking_glass_entered");
        assertThat(entered.getInput()).contains("onto the Looking Glass on desk");

        node.frame("display", null, "{\"lookingGlass\": false}");
        assertThat(node.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("looking_glass_exited");
        node.next("done");
        ChatMessage exited = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(exited.getEventKind()).isEqualTo("node.looking_glass_exited");
        assertThat(exited.getInput()).contains("off the Looking Glass on desk");

        node.frame("display", "d1", null);
        assertThat(node.next("error").getAsJsonObject("data").get("code").getAsString()).isEqualTo("bad_message");
    }

    /** A body whose model has a halo (a shape key of the headgear) described, and the headgear itself. */
    private Body haloBody() throws Exception {
        Path dir = Files.createTempDirectory("vrm");
        File source = UnityFixtures.glb(dir.resolve("m.vrm").toFile(), """
                {"nodes": [{"name": "Headgear", "mesh": 0}],
                 "meshes": [{"extras": {"targetNames": ["Hide Halo"]}, "weights": [0], "primitives": [{}]}],
                 "extensions": {"VRMC_vrm": {"humanoid": {"humanBones": {"hips": {}}},
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.vrm");
        Files.copy(source.toPath(), file.toPath());
        Model ryuu = modelService.find(modelService.attach(new Model(), file, "Ryuu.vrm").getId());
        ryuu.getCapabilityNames().put("key:Headgear/Hide Halo", "golden halo");
        ryuu.getPartShown().put("key:Headgear/Hide Halo", "A golden halo floats above you.");
        ryuu.getPartHidden().put("key:Headgear/Hide Halo", "Nothing floats above you now.");
        ryuu = modelService.save(ryuu);
        Body body = bodyService.create(joi, "Street");
        body.setModel(ryuu);
        body = bodyService.save(body);
        return body;
    }

    @Test
    void aPartSwitchedOnTheNodeIsKeptOnTheBodyAndSheReacts() throws Exception {
        Body body = haloBody();
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.text("My halo!"));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        // she has not chatted yet: kept, and she is not told
        node.frame("part", null, "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        node.frame("part", null, "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"shown\"}");
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");

        node.frame("part", null, "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        JsonObject turn = node.next("turn");
        assertThat(turn.getAsJsonObject("data").get("cause").getAsString()).isEqualTo("part_switched");
        node.next("done");
        // kept on the body: the next start shows it so
        assertThat(bodyService.find(body.getId()).partStateMap()).containsEntry("key:Headgear/Hide Halo", "hidden");
        ChatMessage told = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(told.getType()).isEqualTo(ChatMessageType.EVENT);
        assertThat(told.getEventKind()).isEqualTo("node.part_switched");
        assertThat(told.getInput()).contains("golden halo").contains("Nothing floats above you now.");

        // a part or a state the body does not have
        node.frame("part", null, "{\"part\": \"key:Headgear/Nope\", \"state\": \"hidden\"}");
        assertThat(node.next("error").getAsJsonObject("data").get("code").getAsString()).isEqualTo("bad_message");
    }

    @Test
    void partsSwitchedTogetherAreKeptAtOnceAndToldInOneTurn() throws Exception {
        Body body = haloBody();
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.text("Everything is off!"));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");

        // a part with an id is answered when it is kept, whatever she is told
        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        node.frame("part", "p2", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"shown\"}");
        JsonObject first = node.next("part_kept");
        assertThat(first.get("ref").getAsString()).isEqualTo("p1");
        assertThat(first.getAsJsonObject("data").get("state").getAsString()).isEqualTo("hidden");
        assertThat(node.next("part_kept").get("ref").getAsString()).isEqualTo("p2");
        // hidden, then shown again: back in the default state, kept
        assertThat(bodyService.find(body.getId()).partStateMap()).doesNotContainKey("key:Headgear/Hide Halo");

        // one turn for both
        assertThat(node.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("part_switched");
        node.next("done");
        ChatMessage told = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(told.getEventKind()).isEqualTo("node.part_switched");
        assertThat(told.getInput().lines().filter(line -> line.startsWith("A part of you was switched")).count()).isEqualTo(2);
        assertThat(told.getInput()).contains("Nothing floats above you now.").contains("A golden halo floats above you.");
        assertThat(node.frames.poll(800, TimeUnit.MILLISECONDS)).as("no second turn").isNull();

        // a part the body does not have: an error that refers to the frame, no part_kept
        node.frame("part", "p3", "{\"part\": \"key:Headgear/Nope\", \"state\": \"hidden\"}");
        JsonObject error = node.next("error");
        assertThat(error.get("ref").getAsString()).isEqualTo("p3");
        assertThat(error.getAsJsonObject("data").get("code").getAsString()).isEqualTo("bad_message");
    }

    @Test
    void sheChangesAPartOfHerOwnBodyWithAFunctionAndTheNodeReadsItAgain() throws Exception {
        Body body = haloBody();
        llm.enqueue(MockLLMResponse.text("Hello."),
                MockLLMResponse.text("Fine.").andToolCall("change_body_part", "{\"part\":\"golden halo\",\"state\":\"hidden\"}"),
                MockLLMResponse.text("There. It is gone."),
                MockLLMResponse.toolCall("change_body_part", "{\"part\":\"golden halo\",\"state\":\"glowing\"}"),
                MockLLMResponse.text("Hm, it can't."));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");

        node.frame("say", "t2", "{\"text\": \"Take the halo off.\", \"speak\": false}");
        // the node is told to read her body again
        JsonObject event = node.next("event");
        assertThat(event.getAsJsonObject("data").get("name").getAsString()).isEqualTo("body");
        node.next("done");
        assertThat(bodyService.find(body.getId()).partStateMap()).containsEntry("key:Headgear/Hide Halo", "hidden");
        // she knows what she did: the tool's answer, not an event of her own, and no turn about it
        assertThat(llm.getCompletionRequests().get(2).getLastMessage().content()).contains("Nothing floats above you now.");
        assertThat(node.frames.poll(2500, TimeUnit.MILLISECONDS)).as("no turn about her own change").isNull();

        // a state the part does not have: she is told what it can be, nothing changes
        node.frame("say", "t3", "{\"text\": \"Make it glow.\", \"speak\": false}");
        node.next("done");
        assertThat(llm.getCompletionRequests().get(4).getLastMessage().content()).contains("golden halo can be: shown, hidden");
        assertThat(bodyService.find(body.getId()).partStateMap()).containsEntry("key:Headgear/Hide Halo", "hidden");
    }

    @Test
    void sheFindsAnAnimationOfHerBodyAndTheNodePlaysIt() throws Exception {
        haloBody();
        AnimationPack wave = new AnimationPack();
        wave.setName("Wave");
        wave.setDescription("You wave hello.");
        wave = packService.save(wave);
        llm.enqueue(MockLLMResponse.text("Hello."),
                MockLLMResponse.text("Hi!").andToolCall("play_animation", "{\"animation\":\"Wave\"}"),
                MockLLMResponse.text("There."),
                MockLLMResponse.toolCall("play_animation", "{\"animation\":\"Moonwalk\"}"),
                MockLLMResponse.text("Hm."));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");
        // her prompt asks her to use animations and the body, often
        assertThat(llm.getCompletionRequests().getFirst().getPromptText())
                .contains("Use play_animation in most of your replies").contains("change a part of it with change_body_part");
        assertThat(llm.getCompletionRequests().getFirst().getRawBody()).contains("play_animation").contains("You wave hello.");

        node.frame("say", "t2", "{\"text\": \"Wave!\", \"speak\": false}");
        JsonObject play = node.next("animation");
        assertThat(play.getAsJsonObject("data").get("animation").getAsString()).isEqualTo(wave.getUuid());
        assertThat(play.getAsJsonObject("data").get("name").getAsString()).isEqualTo("Wave");
        node.next("done");
        assertThat(llm.getCompletionRequests().get(2).getLastMessage().content()).contains("You played Wave.");

        // an animation she does not have: she is told which she has, nothing plays
        node.frame("say", "t3", "{\"text\": \"Moonwalk!\", \"speak\": false}");
        node.next("done");
        assertThat(llm.getCompletionRequests().get(4).getLastMessage().content()).contains("no such animation").contains("Wave");
    }

    @Test
    void sheWritesDownWhatSheLearnsAndItComesBackWhenItsKeywordsAreSaid() throws Exception {
        llm.enqueue(MockLLMResponse.text("Hello."),
                MockLLMResponse.text("Noted.").andToolCall("remember",
                        "{\"text\":\"The user's sister is called Mia.\",\"keywords\":\"Mia, sister\",\"importance\":\"important\"}"),
                MockLLMResponse.text("I will not forget."),
                MockLLMResponse.toolCall("recall", "{\"query\":\"sister\"}"),
                MockLLMResponse.text("Mia, right."),
                MockLLMResponse.text("Yes."));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");
        assertThat(llm.getCompletionRequests().getFirst().getPromptText()).doesNotContain("WHAT YOU REMEMBER");

        node.frame("say", "t2", "{\"text\": \"My sister is called Mia.\", \"speak\": false}");
        node.next("done");
        List<Memory> memories = memoryService.findAll(joi);
        assertThat(memories).hasSize(1);
        assertThat(memories.getFirst().getText()).isEqualTo("The user's sister is called Mia.");
        assertThat(memories.getFirst().getKeywords()).isEqualTo("mia, sister");
        assertThat(memories.getFirst().getImportance()).isEqualTo(MemoryImportance.IMPORTANT);
        assertThat(memories.getFirst().getApproved()).isFalse();

        // she looks it up
        node.frame("say", "t3", "{\"text\": \"Who is my sister?\", \"speak\": false}");
        node.next("done");
        assertThat(llm.getCompletionRequests().get(4).getLastMessage().content()).contains("The user's sister is called Mia.");
        // and the important memory is in her prompt from then on
        node.frame("say", "t4", "{\"text\": \"Thanks.\", \"speak\": false}");
        node.next("done");
        assertThat(llm.getCompletionRequests().getLast().getPromptText()).contains("WHAT YOU REMEMBER").contains("- The user's sister is called Mia.");
    }

    @Test
    void anUnimportantMemoryIsRecalledOnlyForItsKeywordsAndSearchScoresThem() throws Exception {
        memoryService.remember(joi, null, "Likes green tea.", "tea", MemoryImportance.MEDIUM);
        memoryService.remember(joi, null, "Allergic to peanuts.", "peanut, allergy", MemoryImportance.IMPORTANT);
        memoryService.remember(joi, null, "Went to Brno once.", "brno", null);
        // the system prompt holds the important ones whatever was said, the message the ones its words call back
        assertThat(memoryService.important(joi, 20)).isEqualTo("- Allergic to peanuts.");
        assertThat(memoryService.recalled(joi, "Hello", 20)).isNull();
        assertThat(memoryService.recalled(joi, "I made some TEA", 20)).isEqualTo("- Likes green tea.");
        assertThat(memoryService.recalled(joi, "tea, brno and a peanut", 1)).isEqualTo("- Went to Brno once.");
        assertThat(memoryService.search(joi, "green tea", 5)).extracting(Memory::getText).containsExactly("Likes green tea.");
        assertThat(memoryService.search(joi, "ab", 5)).isEmpty();
    }

    @Test
    void wordsSaidWithTheEventsTakeThemAlongInTheirOwnTurn() throws Exception {
        haloBody();
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.text("Yes, it is gone."));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");

        // the change and the words typed with it: one turn, the one of the words, told as one message
        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        node.frame("say", "t2", "{\"text\": \"Do you like it?\", \"speak\": false, \"withEvents\": true}");
        assertThat(node.next("part_kept").get("ref").getAsString()).isEqualTo("p1");
        assertThat(node.next("text").get("ref").getAsString()).isEqualTo("t2");
        node.next("reply");
        node.next("done");
        ChatMessage said = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(said.getEventKind()).isNull();
        assertThat(said.getInput()).startsWith("Do you like it?").contains("Nothing floats above you now.");
        assertThat(node.frames.poll(2500, TimeUnit.MILLISECONDS)).as("no second turn about the part").isNull();
    }

    @Test
    void partsSwitchedWhileATurnRunsAreToldTogetherWhenItEnds() throws Exception {
        haloBody();
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.toolCall("desk__note", "{\"text\":\"milk\"}"),
                MockLLMResponse.text("Noted."), MockLLMResponse.text("My halo!"));
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");

        // her turn waits for the node's tool; the parts are switched meanwhile
        node.frame("say", "t2", "{\"text\": \"Note the milk\", \"speak\": false}");
        String callId = node.next("tool_call").getAsJsonObject("data").get("callId").getAsString();
        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        assertThat(node.next("part_kept").get("ref").getAsString()).isEqualTo("p1");
        Thread.sleep(800);
        node.frame("part", "p2", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"shown\"}");
        assertThat(node.next("part_kept").get("ref").getAsString()).isEqualTo("p2");
        assertThat(node.frames.stream().map(frame -> frame.get("type").getAsString())).doesNotContain("turn");

        node.frame("tool_result", null, "{\"callId\": \"" + callId + "\", \"ok\": true, \"content\": \"noted\"}");
        assertThat(node.next("done").get("ref").getAsString()).isEqualTo("t2");
        // the turn about both follows its end
        assertThat(node.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("part_switched");
        node.next("done");
        assertThat(chatMessageService.findLatest(joi, 1).getFirst().getInput().lines()
                .filter(line -> line.startsWith("A part of you was switched")).count()).isEqualTo(2);
        assertThat(node.frames.poll(800, TimeUnit.MILLISECONDS)).as("no second turn").isNull();
    }

    /** A personality that decides on the changes of her parts, her assistant's: the halo body is hers. */
    private Body stubbornJoi() throws Exception {
        Body body = haloBody();
        PersonalitySetting stubborn = new PersonalitySetting("stubborn");
        stubborn.setName("Stubborn");
        stubborn.setPrompt("You are stubborn.");
        stubborn.setPartConsent("If {{user}} begs, consider allowing it.");
        personaService.savePersonality(stubborn);
        joi.setPersonality("stubborn");
        joi = joiService.save(joi);
        return body;
    }

    private Node chattedNode() throws Exception {
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.5");
        node.next("welcome");
        node.manifest(1);
        node.frame("say", "t1", "{\"text\": \"Hi\", \"speak\": false}");
        node.next("done");
        return node;
    }

    @Test
    void aPersonalityThatDecidesCanRefuseAChangeOfAPart() throws Exception {
        Body body = stubbornJoi();
        llm.enqueue(MockLLMResponse.text("Hello."),
                MockLLMResponse.toolCall("decide_part_change", "{\"request\":1,\"decision\":\"refuse\",\"reason\":\"Not in front of the guests.\"}"),
                MockLLMResponse.text("No."));
        Node node = chattedNode();

        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        // nothing is kept until she decided: her turn comes first
        assertThat(node.next("turn").getAsJsonObject("data").get("cause").getAsString()).isEqualTo("part_switched");
        JsonObject refused = node.next("part_refused");
        assertThat(refused.get("ref").getAsString()).isEqualTo("p1");
        assertThat(refused.getAsJsonObject("data").get("reason").getAsString()).isEqualTo("Not in front of the guests.");
        node.next("done");
        assertThat(bodyService.find(body.getId()).partStateMap()).doesNotContainKey("key:Headgear/Hide Halo");
        assertThat(node.frames.stream().map(frame -> frame.get("type").getAsString())).doesNotContain("part_kept");
        // how she decides is what her personality says, told with the request
        String told = chatMessageService.findLatest(joi, 1).getFirst().getInput();
        assertThat(told).contains("Request 1:").contains("How you decide: If").contains("begs, consider allowing it.")
                .contains("decide_part_change");
    }

    @Test
    void aPersonalityThatDecidesCanAllowAChangeOfAPart() throws Exception {
        Body body = stubbornJoi();
        llm.enqueue(MockLLMResponse.text("Hello."),
                MockLLMResponse.toolCall("decide_part_change", "{\"request\":1,\"decision\":\"allow\"}"),
                MockLLMResponse.text("Fine."));
        Node node = chattedNode();

        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        JsonObject kept = node.next("part_kept");
        assertThat(kept.get("ref").getAsString()).isEqualTo("p1");
        // she was told already: the node has no reaction to wait for
        assertThat(kept.getAsJsonObject("data").get("told").getAsBoolean()).isFalse();
        node.next("done");
        assertThat(bodyService.find(body.getId()).partStateMap()).containsEntry("key:Headgear/Hide Halo", "hidden");
        assertThat(node.frames.poll(800, TimeUnit.MILLISECONDS)).as("she is not told again").isNull();
    }

    @Test
    void aRequestSheDoesNotAnswerIsAllowed() throws Exception {
        Body body = stubbornJoi();
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.text("Hmm, whatever."));
        Node node = chattedNode();

        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"hidden\"}");
        node.next("turn");
        JsonObject kept = node.next("part_kept");
        assertThat(kept.get("ref").getAsString()).isEqualTo("p1");
        assertThat(bodyService.find(body.getId()).partStateMap()).containsEntry("key:Headgear/Hide Halo", "hidden");
    }

    @Test
    void aRequestThatChangesNothingIsKeptAtOnce() throws Exception {
        stubbornJoi();
        llm.enqueue(MockLLMResponse.text("Hello."));
        Node node = chattedNode();

        // the halo is shown by default: asking for it shown is nothing to decide
        node.frame("part", "p1", "{\"part\": \"key:Headgear/Hide Halo\", \"state\": \"shown\"}");
        JsonObject kept = node.next("part_kept");
        assertThat(kept.get("ref").getAsString()).isEqualTo("p1");
        assertThat(node.frames.poll(800, TimeUnit.MILLISECONDS)).as("no turn").isNull();
    }

    @Test
    void aTurnIsStopped() throws Exception {
        Node node = connect("Bearer " + token);
        node.hello("n1", "desk", joi.getUuid(), "1.2");
        node.next("welcome");
        node.manifest(1);
        llm.enqueue(MockLLMResponse.toolCall("desk__note", "{\"text\":\"milk\"}"));
        node.frame("say", "t1", "{\"text\": \"Remember the milk\", \"speak\": false}");
        String callId = node.next("tool_call").getAsJsonObject("data").get("callId").getAsString();
        node.frame("stop", null, "{\"turn\": \"t1\"}");
        JsonObject cancel = node.next("tool_cancel");
        assertThat(cancel.getAsJsonObject("data").get("callId").getAsString()).isEqualTo(callId);
        JsonObject stopped = node.next("error");
        assertThat(stopped.get("ref").getAsString()).isEqualTo("t1");
        assertThat(stopped.getAsJsonObject("data").get("code").getAsString()).isEqualTo("stopped");
        assertThat(chatMessageService.findLatest(joi, 10)).isEmpty();
    }
}
