package com.github.enerccio.joi.domain.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.extensions.ExtensionPart;
import com.github.enerccio.joi.extensions.JoiExtension;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** What an extension of Joi may add: functions for her turns, tabs, turn listeners; and that all of it goes when it does. */
class JoiExtensionsTest extends PlatformTestBase {

    private static final Logger LOG = LoggerFactory.getLogger("extension.test-ext");

    @Autowired
    private JoiExtensions extensions;
    @Autowired
    private JoiExtensionLoader loader;
    @Autowired
    private ChatService chatService;
    @Autowired
    private JoiService joiService;

    private Joi joi;

    /** The part of a bundle the loader asks: its symbolic name. */
    private static Bundle bundle(String name) {
        return (Bundle) Proxy.newProxyInstance(JoiExtensionsTest.class.getClassLoader(), new Class<?>[] {Bundle.class},
                (_, method, _) -> method.getName().equals("getSymbolicName") ? name : null);
    }

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.create("Mira");
        joi.setChatModel(createAI().getId());
        joi = joiService.save(joi);
    }

    @AfterEach
    void tearDown() {
        extensions.unregisterAll("test-ext");
        extensions.unregisterAll("other-ext");
    }

    private void chat(String text) throws Exception {
        chatService.chat(joi, new TurnInput(text, null, false, false), null).get(30, TimeUnit.SECONDS);
    }

    private static FunctionDefinition echo(String name, List<String> calls) {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        return FunctionDefinition.dynamic(name, "Says it back", schema, (continuation, arguments) -> {
            calls.add((String) arguments.get("text"));
            continuation.complete("echo " + arguments.get("text"));
        });
    }

    /** An extension that registers one of each; {@code asked} gets the assistants the tools were asked for. */
    private JoiExtension extension(List<String> calls, List<String> asked, List<String> seen) {
        return (JoiExtension) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {JoiExtension.class}, (_, method, args) -> {
            if (method.getName().equals("onJoiLoad")) {
                JoiExtensionContext context = (JoiExtensionContext) args[1];
                assertThat(context.owner()).isEqualTo("test-ext");
                assertThat(context.bean(ChatService.class)).isNotNull();
                context.offerTools(joiOfTheTurn -> {
                    asked.add(joiOfTheTurn.getUuid());
                    return List.of(echo("test_echo", calls));
                });
                context.addWorkspacePart(new ExtensionPart() {
                    @Override
                    public String key() {
                        return "tab";
                    }

                    @Override
                    public String caption() {
                        return "Test";
                    }

                    @Override
                    public com.vaadin.flow.component.Component create() {
                        return new com.vaadin.flow.component.html.Div("test");
                    }
                });
                context.onTurnEvent(TurnEvents.BEFORE_INFERENCE, (event, chain) -> {
                    seen.add("before inference");
                    chain.next();
                });
            }
            return null;
        });
    }

    @Test
    void anExtensionOffersFunctionsAddsATabAndListensToTheTurns() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        loader.load(extension(calls, asked, seen), bundle("test-ext"), LOG);
        llm.enqueue(MockLLMResponse.text("Echoing.").andToolCall("test_echo", "{\"text\":\"hello\"}"), MockLLMResponse.text("Done."));

        chat("Say hello through the extension");

        assertThat(calls).containsExactly("hello");
        assertThat(asked).containsExactly(joi.getUuid());
        assertThat(seen).isNotEmpty();
        assertThat(extensions.parts()).hasSize(1);
        assertThat(extensions.parts().getFirst().key()).isEqualTo("ext:test-ext:tab");
        assertThat(extensions.parts().getFirst().part().caption()).isEqualTo("Test");
    }

    @Test
    void whatTheExtensionRegisteredGoesWithIt() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        loader.load(extension(calls, asked, seen), bundle("test-ext"), LOG);

        extensions.unregisterAll("test-ext");
        llm.enqueue(MockLLMResponse.text("Nothing offered."));
        chat("Anything?");

        assertThat(asked).isEmpty();
        assertThat(seen).isEmpty();
        assertThat(extensions.parts()).isEmpty();
    }

    @Test
    void anExtensionThatFailsToLoadLeavesNothingBehind() throws Exception {
        JoiExtension failing = (JoiExtension) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {JoiExtension.class},
                (_, method, args) -> {
                    if (method.getName().equals("onJoiLoad")) {
                        JoiExtensionContext context = (JoiExtensionContext) args[1];
                        context.offerTools(_ -> List.of(echo("test_echo", new CopyOnWriteArrayList<>())));
                        throw new IllegalStateException("not today");
                    }
                    return null;
                });

        loader.load(failing, bundle("test-ext"), LOG);
        llm.enqueue(MockLLMResponse.text("Hi."));
        chat("Hi");

        assertThat(llm.getCompletionRequests().getFirst().getPromptText()).doesNotContain("test_echo");
        assertThat(extensions.parts()).isEmpty();
    }

    @Test
    void aFunctionThatIsTakenAlreadyIsNotOfferedAndOneExtensionDoesNotStopAnother() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> theirs = new CopyOnWriteArrayList<>();
        JoiExtension broken = (JoiExtension) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {JoiExtension.class},
                (_, method, args) -> {
                    if (method.getName().equals("onJoiLoad")) {
                        ((JoiExtensionContext) args[1]).offerTools(_ -> {
                            throw new IllegalStateException("broken");
                        });
                    }
                    return null;
                });
        loader.load(broken, bundle("other-ext"), LOG);
        loader.load(extension(calls, theirs, new CopyOnWriteArrayList<>()), bundle("test-ext"), LOG);
        llm.enqueue(MockLLMResponse.text("Echoing.").andToolCall("test_echo", "{\"text\":\"still here\"}"), MockLLMResponse.text("Done."));

        chat("Say it");

        assertThat(calls).containsExactly("still here");
    }
}
