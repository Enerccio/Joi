package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The node's end of the protocol: frames to the listener, a say, ping and tool calls answered. */
class NodeSocketTest {

    @Test
    void framesReachTheListenerAndCallsAreAnswered() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        LinkedBlockingQueue<JsonObject> sent = new LinkedBlockingQueue<>();
        NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void turnStarted(String turn, String cause) {
                seen.add("turn " + turn + " " + cause);
            }

            @Override
            public void text(String turn, String delta) {
                seen.add("text " + turn + " " + delta);
            }

            @Override
            public void reply(String turn, ChatReply reply) {
                seen.add("reply " + turn + " " + reply.text());
            }

            @Override
            public void failed(String ref, ApiError error) {
                seen.add("failed " + ref + " " + error.code());
            }
        });
        socket.sendTo(text -> sent.add(JsonParser.parseString(text).getAsJsonObject()));
        socket.setTools(call -> new ToolResult(call.callId(), true, "noted " + call.arguments().get("text").getAsString(), null));

        String turn = socket.say("Hi", false, false);
        JsonObject say = sent.poll(1, TimeUnit.SECONDS);
        assertThat(say.get("type").getAsString()).isEqualTo("say");
        assertThat(say.get("id").getAsString()).isEqualTo(turn);

        socket.receive("{\"type\":\"turn\",\"data\":{\"turn\":\"s1\",\"cause\":\"first_boot\"}}");
        socket.receive("{\"type\":\"text\",\"ref\":\"s1\",\"data\":{\"delta\":\"Hel\"}}");
        socket.receive("{\"type\":\"reply\",\"ref\":\"s1\",\"data\":{\"messageId\":\"m\",\"text\":\"Hello.\",\"usage\":{\"promptTokens\":1,"
                + "\"completionTokens\":1,\"firstTokenMs\":1,\"textMs\":1,\"speechMs\":-1}}}");
        socket.receive("{\"type\":\"error\",\"ref\":\"" + turn + "\",\"data\":{\"code\":\"stopped\",\"message\":\"Stopped\"}}");
        socket.receive("{\"type\":\"hologram\"}");
        socket.receive("not json");
        assertThat(seen).containsExactly("turn " + turn + " say", "turn s1 first_boot", "text s1 Hel", "reply s1 Hello.",
                "failed " + turn + " stopped");

        socket.receive("{\"type\":\"ping\",\"id\":\"p1\"}");
        JsonObject pong = sent.poll(1, TimeUnit.SECONDS);
        assertThat(pong.get("type").getAsString()).isEqualTo("pong");
        assertThat(pong.get("ref").getAsString()).isEqualTo("p1");

        socket.receive("{\"type\":\"tool_call\",\"data\":{\"callId\":\"c1\",\"tool\":\"note\",\"arguments\":{\"text\":\"milk\"},"
                + "\"timeoutSeconds\":60}}");
        JsonObject result = sent.poll(5, TimeUnit.SECONDS);
        assertThat(result.get("type").getAsString()).isEqualTo("tool_result");
        assertThat(result.getAsJsonObject("data").get("content").getAsString()).isEqualTo("noted milk");
        assertThat(result.getAsJsonObject("data").get("ok").getAsBoolean()).isTrue();
    }

    @Test
    void aTaskThatWaitsIsAQuestionUntilAnotherFrameTakesItBack() {
        List<String> seen = new CopyOnWriteArrayList<>();
        TaskQuestions questions = new TaskQuestions();
        NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void task(com.github.enerccio.joi.protocol.http.TaskInfo task) {
                seen.add(task.id() + " " + task.status() + (questions.take(task) != null ? " asked" : ""));
            }
        });
        String waiting = "{\"type\":\"task\",\"data\":{\"id\":\"t1\",\"title\":\"Prague\",\"tool\":\"web_search\",\"status\":\"waiting\","
                + "\"created\":\"2026-11-20T10:00:00Z\"}}";
        socket.receive(waiting);
        socket.receive(waiting);
        assertThat(questions.oldest().id()).isEqualTo("t1");
        socket.receive("{\"type\":\"task\",\"data\":{\"id\":\"t1\",\"title\":\"Prague\",\"tool\":\"web_search\",\"status\":\"queued\","
                + "\"created\":\"2026-11-20T10:00:00Z\"}}");
        assertThat(questions.oldest()).isNull();
        assertThat(seen).containsExactly("t1 waiting asked", "t1 waiting", "t1 queued");
    }
}
