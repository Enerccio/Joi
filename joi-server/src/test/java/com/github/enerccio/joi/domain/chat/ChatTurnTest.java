package com.github.enerccio.joi.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.components.CallTextGuard;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.chat.turn.TurnOutcome;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.marginalia.domain.collections.ReasoningPassthrough;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.joi.domain.collections.MemoryImportance;
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
import com.github.enerccio.marginalia.domain.service.job.FinishedJob;
import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.marginalia.domain.service.job.JobStatus;
import com.github.enerccio.marginalia.domain.service.job.Jobs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.function.Consumer;
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
    private com.github.enerccio.joi.domain.service.MemoryService memoryService;
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
    void aTurnIsOneJobWithAllItsRequestsAsCalls() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        offerNote(calls);
        List<FinishedJob> jobs = new CopyOnWriteArrayList<>();
        Consumer<FinishedJob> sink = jobs::add;
        Jobs.addSink(sink);
        try {
            llm.enqueue(MockLLMResponse.text("Let me note it.").andToolCall("note", "{\"text\":\"milk\"}"),
                    MockLLMResponse.text("Noted the milk."));

            chat("Remember the milk", null);

            // the two rounds of the turn (the call and its result) are two calls of one job, owned by the user
            List<FinishedJob> turns = jobs.stream().filter(job -> "chat".equals(job.kind())).toList();
            assertThat(turns).hasSize(1);
            FinishedJob turn = turns.getFirst();
            assertThat(turn.label()).isEqualTo("Mira");
            assertThat(turn.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(turn.userId()).isNotNull();
            assertThat(turn.calls()).hasSize(2).allSatisfy(call -> assertThat(call.type()).isEqualTo(JobCallType.LLM));
            assertThat(jobs.stream().filter(job -> "untagged".equals(job.kind()))).isEmpty();
            // nothing runs any more
            assertThat(chatService.running()).isEmpty();
        } finally {
            Jobs.removeSink(sink);
        }
    }

    @Test
    void aStoppedTurnIsACancelledJob() throws Exception {
        List<FinishedJob> jobs = new CopyOnWriteArrayList<>();
        Consumer<FinishedJob> sink = jobs::add;
        Jobs.addSink(sink);
        try {
            registrations.add(chatService.addEventListener(TurnEvents.BEFORE_INFERENCE, (event, chain) -> {
                event.getCancellationToken().cancel();
                chain.next();
            }));
            llm.enqueue(MockLLMResponse.text("never said"));

            CompletableFuture<ChatService.Result> stopped = chatService.chat(joi, new TurnInput("Hello", null, false, false), null);
            assertThatThrownBy(() -> stopped.get(30, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);

            FinishedJob turn = jobs.stream().filter(job -> "chat".equals(job.kind())).findFirst().orElseThrow();
            assertThat(turn.status()).isEqualTo(JobStatus.CANCELLED);
        } finally {
            Jobs.removeSink(sink);
        }
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
        // the tools used are stored with the message, in order
        assertThat(com.github.enerccio.joi.domain.chat.ToolUsage.fromJson(result.message().getToolCalls()))
                .containsExactly(new ToolUsage("note", "{\"text\":\"milk\"}", "noted milk", false));
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
    void aMessageReadsTheSameInEveryPromptAfterItAndTheSystemPromptHasNoClock() throws Exception {
        llm.enqueue(MockLLMResponse.text("One."), MockLLMResponse.text("Two."), MockLLMResponse.text("Three."));
        chat("First", new ChatService.Listener() {
        });
        chat("Second", new ChatService.Listener() {
        });
        chat("Third", new ChatService.Listener() {
        });
        List<MockLLMRequest> requests = llm.getCompletionRequests();

        // the first message of the prompt has the time line of when it was said, and her own replies have none
        String first = requests.get(0).getLastMessage().content();
        assertThat(first).matches("(?s)\\[[A-Z][a-z]+, [A-Z][a-z]+ \\d+, \\d{4}, \\d+:\\d\\d [AP]M\\]\nFirst");
        // the clock is not in the system prompt: it would break the model's cache of it with every minute
        assertThat(requests.get(0).getMessages("system").getFirst()).doesNotContain("It is ").doesNotContain("{{");
        assertThat(requests.get(0).getMessages("system").getFirst()).contains("Lines in square brackets");

        // no pause between them: the later ones have no line, and every message reads in the later prompts as it read
        // when it was the newest
        assertThat(requests.get(1).getMessages().get(1).content()).isEqualTo(first);
        assertThat(requests.get(1).getLastMessage().content()).isEqualTo("Second");
        assertThat(requests.get(2).getMessages().get(1).content()).isEqualTo(first);
        assertThat(requests.get(2).getMessages().get(3).content()).isEqualTo("Second");
        assertThat(requests.get(2).getMessages().get(2).content()).isEqualTo("One.");
    }

    @Test
    void theMemoriesThatFitAMessageAreAddedToItAndTheImportantOnesAreInTheSystemPrompt() throws Exception {
        memoryService.remember(joi, null, "Allergic to peanuts.", "peanut", MemoryImportance.IMPORTANT);
        memoryService.remember(joi, null, "Likes green tea.", "tea", MemoryImportance.MEDIUM);
        llm.enqueue(MockLLMResponse.text("Hello."), MockLLMResponse.text("Tea!"), MockLLMResponse.text("Yes."));
        chat("Hi", new ChatService.Listener() {
        });
        chat("I made some tea", new ChatService.Listener() {
        });
        chat("Thanks", new ChatService.Listener() {
        });
        List<MockLLMRequest> requests = llm.getCompletionRequests();

        // the system prompt is the same in every prompt, the always recalled memory is in it, the other is not
        String system = requests.get(0).getMessages("system").getFirst();
        assertThat(system).contains("- Allergic to peanuts.").doesNotContain("Likes green tea.");
        assertThat(requests.get(1).getMessages("system").getFirst()).isEqualTo(system);
        assertThat(requests.get(2).getMessages("system").getFirst()).isEqualTo(system);
        assertThat(requests.get(0).getLastMessage().content()).doesNotContain("Likes green tea.");

        // the one that fits is after the message that called it, in this prompt only: it is not stored with it
        assertThat(requests.get(1).getLastMessage().content()).startsWith("I made some tea\n[You remember").contains("- Likes green tea.");
        assertThat(requests.get(2).getMessages().get(3).content()).isEqualTo("I made some tea");
    }

    @Test
    void theThinkingOfARoundThatCalledToolsGoesBackWithItsCallsWhenTheModelWantsIt() throws Exception {
        // a model set to get its thoughts back between the calls (Gemma 4) does, and the thinking of the turn is stored
        OpenAICompatible ai = createAI();
        ai.setReasoningPassthrough(ReasoningPassthrough.TOOL_CALLS);
        aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        offerNote(new CopyOnWriteArrayList<>());
        llm.enqueue(MockLLMResponse.text("Let me note it.").withReasoning("I should note the milk.").andToolCall("note", "{\"text\":\"milk\"}"),
                MockLLMResponse.text("Noted.").withReasoning("It is done."));

        ChatService.Result result = chat("Remember the milk", new ChatService.Listener() {
        });

        assertThat(result.message().getResponseReasoning()).isEqualTo("I should note the milk.\n\nIt is done.");
        JsonArray messages = llm.getCompletionRequests().get(1).getBody().getAsJsonArray("messages");
        com.google.gson.JsonObject calling = null;
        for (com.google.gson.JsonElement message : messages) {
            if (message.getAsJsonObject().has("tool_calls")) {
                calling = message.getAsJsonObject();
            }
        }
        assertThat(calling).isNotNull();
        assertThat(calling.get("reasoning_content").getAsString()).isEqualTo("I should note the milk.");
    }

    @Test
    void theThinkingSwitchOfTheModelIsSentAndAChangeOfItReachesTheNextTurn() throws Exception {
        OpenAICompatible ai = createAI();
        ai.getAdditionalParameters().add("chat_template_kwargs", com.google.gson.JsonParser.parseString("{\"enable_thinking\": true}"));
        aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        llm.enqueue(MockLLMResponse.text("One."), MockLLMResponse.text("Two."));

        chat("First", new ChatService.Listener() {
        });
        // as the user changes it in the model's dialog while the server runs
        OpenAICompatible edited = (OpenAICompatible) aiService.findForUser(ai.getUuid());
        edited.getAdditionalParameters().add("chat_template_kwargs", com.google.gson.JsonParser.parseString("{\"enable_thinking\": false}"));
        aiService.save(edited);
        chat("Second", new ChatService.Listener() {
        });

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        assertThat(requests.get(0).getBody().getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean()).isTrue();
        assertThat(requests.get(1).getBody().getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean()).isFalse();
    }

    @Test
    void aRoundThatUsesItsWholeBudgetWithoutAnsweringFailsTheTurnInsteadOfStoringNothing() throws Exception {
        OpenAICompatible ai = createAI();
        ai.setMaxCompletionTokens(50);
        aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        llm.enqueue(MockLLMResponse.text("").usage(100, 50));

        assertThatThrownBy(() -> chat("Good morning!", new ChatService.Listener() {
        })).hasMessageContaining("whole budget of 50 tokens");
        // nothing is stored: a blank message is no answer
        assertThat(chatMessageService.findLatest(joi, 10)).isEmpty();

        // the next turn runs as usual
        llm.enqueue(MockLLMResponse.text("Good morning!").usage(100, 12));
        assertThat(chat("Hello?", new ChatService.Listener() {
        }).message().getResponse()).isEqualTo("Good morning!");
    }

    @Test
    void aRoundThatOpensWithACallWrittenAsTextIsAskedAgainWhenTheModelIsSetToGuardIt() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        offerNote(calls);
        OpenAICompatible ai = createAI();
        CallTextGuard.set(ai, true);
        aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        // the first two answers are the call as words, the third is words
        llm.enqueue(MockLLMResponse.text("note(text=\"milk\")"), MockLLMResponse.text("note(text=\"milk\") Noted."),
                MockLLMResponse.text("Noted the milk."));
        StringBuilder shown = new StringBuilder();

        ChatService.Result result = chat("Remember the milk", new ChatService.Listener() {
            @Override
            public void onText(String chunk) {
                shown.append(chunk);
            }
        });

        assertThat(result.message().getResponse()).isEqualTo("Noted the milk.");
        assertThat(shown.toString()).isEqualTo("Noted the milk.");
        assertThat(llm.getCompletionRequests()).hasSize(3);
    }

    @Test
    void aCallWrittenAsTextIsLeftAloneWhenTheModelIsNotSetToGuardIt() throws Exception {
        offerNote(new CopyOnWriteArrayList<>());
        llm.enqueue(MockLLMResponse.text("note(text=\"milk\")"));

        assertThat(chat("Remember the milk", new ChatService.Listener() {
        }).message().getResponse()).isEqualTo("note(text=\"milk\")");
        assertThat(llm.getCompletionRequests()).hasSize(1);
    }

    @Test
    void theFunctionCallRuleOfTheModelGoesIntoTheLatestUserMessageOfEveryRound() throws Exception {
        offerNote(new CopyOnWriteArrayList<>());
        OpenAICompatible ai = createAI();
        ai.setFunctionRule("You act only through function calls.");
        aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        llm.enqueue(MockLLMResponse.text("").andToolCall("note", "{\"text\":\"milk\"}"), MockLLMResponse.text("Noted."));

        chat("Remember the milk", new ChatService.Listener() {
        });

        List<MockLLMRequest> requests = llm.getCompletionRequests();
        assertThat(requests).hasSize(2);
        // the rule follows the text of the user's message, not the system prompt
        assertThat(requests.getFirst().getMessages().stream().filter(m -> "user".equals(m.role())).reduce((a, b) -> b).orElseThrow()
                .content()).contains("Remember the milk").endsWith("You act only through function calls.");
        assertThat(requests.getFirst().getMessages().getFirst().content()).doesNotContain("act only through function calls");
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
