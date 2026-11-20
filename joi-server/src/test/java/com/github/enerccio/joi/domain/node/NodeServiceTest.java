package com.github.enerccio.joi.domain.node;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.NodeService;
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
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(node -> nodeService.closed(node.session));
        ((NodeServiceImpl) nodeService).setGreeting(true);
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
