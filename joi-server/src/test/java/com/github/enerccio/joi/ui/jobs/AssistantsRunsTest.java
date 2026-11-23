package com.github.enerccio.joi.ui.jobs;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** What the assistants do now, the second list of the page {@code /jobs}. */
class AssistantsRunsTest extends PlatformTestBase {

    @Autowired
    private ChatService chatService;
    @Autowired
    private JoiService joiService;

    private Registration hold;
    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        UI.setCurrent(new UI());
        login();
        joi = joiService.create("Mira");
        joi.setChatModel(createAI().getId());
        joi = joiService.save(joi);
    }

    @AfterEach
    void tearDown() {
        if (hold != null) {
            hold.unregister();
        }
        UI.setCurrent(null);
    }

    /**
     * What the assistants of this test's user do. The list itself is the administrator's view of every user's assistants, and the turns and
     * tasks that other test classes left running or waiting are in it (the order the classes run in decides), so the test looks at its own.
     */
    private List<AssistantsRunsGrid.Activity> activities() throws Exception {
        String mine = joi.getOwner().getLogin();
        return new AssistantsRunsGrid().activities().stream().filter(row -> row.user().equals(mine)).toList();
    }

    @Test
    void nothingIsShownWhileNoAssistantDoesAnything() throws Exception {
        assertThat(activities()).isEmpty();
    }

    @Test
    void aTurnThatRunsAndOneThatWaitsForItAreShownWithTheAssistant() throws Exception {
        CountDownLatch inference = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        hold = chatService.addEventListener(TurnEvents.BEFORE_INFERENCE, (event, chain) -> {
            if (first.getAndSet(false)) {
                inference.countDown();
                CompletableFuture.runAsync(() -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    chain.next();
                });
            } else {
                chain.next();
            }
        });
        llm.enqueue(MockLLMResponse.text("One."), MockLLMResponse.text("Two."));

        CompletableFuture<ChatService.Result> running = chatService.chat(joi, new TurnInput("First", null, false, false), null);
        assertThat(inference.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ChatService.Result> waiting = chatService.chat(joi, new TurnInput("Second", null, false, false), null);

        List<AssistantsRunsGrid.Activity> shown = activities();
        assertThat(shown).hasSize(2).allSatisfy(row -> {
            assertThat(row.assistant()).isEqualTo("Mira");
            assertThat(row.user()).isNotBlank();
            assertThat(row.what()).isEqualTo("Chat turn");
        });
        assertThat(shown.stream().map(AssistantsRunsGrid.Activity::state)).anyMatch(state -> state.startsWith("Running"))
                .anyMatch(state -> state.equals("Waits for the turn before it"));

        release.countDown();
        running.get(30, TimeUnit.SECONDS);
        waiting.get(30, TimeUnit.SECONDS);
        assertThat(activities()).isEmpty();
    }
}
