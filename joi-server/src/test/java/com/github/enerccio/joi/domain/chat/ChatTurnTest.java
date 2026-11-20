package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.chat.turn.TurnOutcome;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMRequest;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A chat turn as its steps and events run it: the functions she calls, listeners that change or stop it, one at a time. */
class ChatTurnTest extends PlatformTestBase {

    @Autowired
    private ChatService chatService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;

    private final List<Registration> registrations = new ArrayList<>();
    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.create("Mira");
        joi.setChatModel(createAI().getId());
        joi = joiService.save(joi);
    }

    @AfterEach
    void tearDown() {
        // the chat service is shared by every test
        registrations.forEach(Registration::unregister);
    }

    private ChatService.Result chat(String text, ChatService.Listener listener) throws Exception {
        return chatService.chat(joi, new TurnInput(text, null, false, false), listener).get(30, TimeUnit.SECONDS);
    }

    /** A function {@code note(text)} offered to every turn; {@code calls} gets what it was called with. */
    private void offerNote(List<String> calls) {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        FunctionDefinition note = FunctionDefinition.dynamic("note", "Notes a thing down", schema, (continuation, arguments) -> {
            calls.add((String) arguments.get("text"));
            continuation.complete("noted " + arguments.get("text"));
        });
        registrations.add(chatService.addEventListener(TurnEvents.COLLECT_TOOLS, (event, chain) -> {
            event.setFunctions(event.getFunctions().and(FunctionSet.bind(List.of(note), this)));
            chain.next();
        }));
    }

    @Test
    void sheCallsAFunctionAndTheModelGetsItsResult() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        offerNote(calls);
        List<TurnEvents> seen = new CopyOnWriteArrayList<>();
        for (TurnEvents event : TurnEvents.values()) {
            registrations.add(chatService.addEventObserver(event, observation -> seen.add(observation.getEvent())));
        }
        registrations.add(chatService.addEventListener(TurnEvents.BEFORE_STORE, (event, chain) -> {
            event.setReply(event.getReply() + " ♥");
            chain.next();
        }));
        llm.enqueue(MockLLMResponse.text("Let me note it.").andToolCall("note", "{\"text\":\"milk\"}"),
                MockLLMResponse.text("Noted the milk."));
        List<String> told = new CopyOnWriteArrayList<>();

        ChatService.Result result = chat("Remember the milk", new ChatService.Listener() {
            @Override
            public void onToolCall(String name, String arguments, String result, boolean failed) {
                told.add(name + " " + result + " " + failed);
            }
        });

        assertThat(calls).containsExactly("milk");
        assertThat(told).containsExactly("note noted milk false");
        assertThat(result.message().getResponse()).isEqualTo("Let me note it.\n\nNoted the milk. ♥");
        assertThat(chatMessageService.findLatest(joi, 10)).extracting(ChatMessage::getResponse)
                .containsExactly("Let me note it.\n\nNoted the milk. ♥");

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        assertThat(requests).hasSize(2);
        JsonArray tools = requests.getFirst().getBody().getAsJsonArray("tools");
        // hers (the memory functions of the server come with it), by name
        assertThat(tools.asList().stream().map(tool -> tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()))
                .contains("note", "remember", "recall");
        assertThat(requests.get(1).getLastMessage().role()).isEqualTo("tool");
        assertThat(requests.get(1).getLastMessage().content()).isEqualTo("noted milk");

        assertThat(seen.stream().filter(event -> !event.isObservedOnly()).toList()).containsExactly(
                TurnEvents.BEFORE_PREPARE, TurnEvents.AFTER_TEMPLATE_DATA, TurnEvents.AFTER_PREPARE_PAYLOAD,
                TurnEvents.COLLECT_TOOLS, TurnEvents.BEFORE_INFERENCE, TurnEvents.AFTER_INFERENCE,
                TurnEvents.BEFORE_TOOL_CALL, TurnEvents.AFTER_TOOL_CALL, TurnEvents.BEFORE_INFERENCE,
                TurnEvents.AFTER_INFERENCE, TurnEvents.BEFORE_STORE, TurnEvents.AFTER_STORE, TurnEvents.AFTER_CLEANUP);
        assertThat(seen).contains(TurnEvents.CHUNK_RECEIVED);
    }

    @Test
    void aListenerAnswersACallLaterInsteadOfTheFunction() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        offerNote(calls);
        registrations.add(chatService.addEventListener(TurnEvents.BEFORE_TOOL_CALL, (event, chain) -> {
            LLMToolCall call = event.getProperty(TurnProperties.TOOL_CALL);
            assertThat(call.getName()).isEqualTo("note");
            // the user answers on another thread, later: nothing waits for it
            CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
                event.getProperties().put(TurnProperties.TOOL_RESULT, "The user said no");
                event.getProperties().put(TurnProperties.TOOL_FAILED, true);
                chain.next();
            });
        }));
        llm.enqueue(MockLLMResponse.toolCall("note", "{\"text\":\"milk\"}"), MockLLMResponse.text("Fine, I won't."));

        ChatService.Result result = chat("Remember the milk", new ChatService.Listener() {
        });

        assertThat(calls).isEmpty();
        assertThat(result.message().getResponse()).isEqualTo("Fine, I won't.");
        assertThat(llm.getCompletionRequests().get(1).getLastMessage().content()).isEqualTo("The user said no");
    }

    @Test
    void aStoppedTurnIsNotStoredAndTheNextOneRuns() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(true);
        registrations.add(chatService.addEventListener(TurnEvents.BEFORE_INFERENCE, (event, chain) -> {
            if (stop.getAndSet(false)) {
                event.getCancellationToken().cancel();
            }
            chain.next();
        }));
        List<TurnOutcome> outcomes = new CopyOnWriteArrayList<>();
        registrations.add(chatService.addEventListener(TurnEvents.AFTER_CLEANUP, (event, chain) -> {
            outcomes.add(event.getProperty(TurnProperties.OUTCOME));
            chain.next();
        }));
        llm.enqueue(MockLLMResponse.text("Hello."));

        CompletableFuture<ChatService.Result> stopped = chatService.chat(joi, new TurnInput("Hi", null, false, false), null);
        assertThatThrownBy(() -> stopped.get(30, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(llm.getCompletionRequests()).isEmpty();
        assertThat(chatMessageService.findLatest(joi, 10)).isEmpty();

        assertThat(chat("Hi again", null).message().getResponse()).isEqualTo("Hello.");
        assertThat(outcomes).containsExactly(TurnOutcome.STOPPED, TurnOutcome.COMPLETED);
    }

    @Test
    void turnsOfOneAssistantRunOneAtATime() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        registrations.add(chatService.addEventObserver(TurnEvents.BEFORE_PREPARE, _ -> started.incrementAndGet()));
        AtomicBoolean hold = new AtomicBoolean(true);
        registrations.add(chatService.addEventListener(TurnEvents.BEFORE_INFERENCE, (event, chain) -> {
            if (hold.getAndSet(false)) {
                CompletableFuture.runAsync(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    chain.next();
                });
            } else {
                chain.next();
            }
        }));
        llm.enqueue(MockLLMResponse.text("One."), MockLLMResponse.text("Two."));

        CompletableFuture<ChatService.Result> first = chatService.chat(joi, new TurnInput("First", null, false, false), null);
        CompletableFuture<ChatService.Result> second = chatService.chat(joi, new TurnInput("Second", null, false, false), null);
        Thread.sleep(300);
        assertThat(started.get()).isEqualTo(1);
        assertThat(second).isNotDone();

        release.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS).message().getResponse()).isEqualTo("One.");
        assertThat(second.get(30, TimeUnit.SECONDS).message().getResponse()).isEqualTo("Two.");
        assertThat(chatMessageService.findLatest(joi, 10)).extracting(ChatMessage::getInput).containsExactly("First", "Second");
    }
}
