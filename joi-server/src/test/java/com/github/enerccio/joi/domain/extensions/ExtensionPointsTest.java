package com.github.enerccio.joi.domain.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskMessageService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionSetting;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtension;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.joi.ui.tools.ExtensionSettingsPanel;
import com.github.enerccio.joi.ui.tools.ToolPermissionsPanel;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The extension points a plugin like Marginalia's books needs: settings of an extension (texts and protected secrets along the chain),
 * the chain of permissions for an extension's functions, and a function that runs as a task of the stronger model.
 */
class ExtensionPointsTest extends PlatformTestBase {

    private static final Logger LOG = LoggerFactory.getLogger("extension.test-ext");
    private static final String OWNER = "test-ext";
    private static final ExtensionSettings SETTINGS = new ExtensionSettings("Test library", "Where the books are",
            List.of(ExtensionSetting.text("url", "Address", null), ExtensionSetting.secret("key", "API key", "Made in the library")));
    private static final ExtensionTool SHELF = new ExtensionTool("test_shelf", "The shelf", "Lists the books", ToolPermission.ALLOW);
    private static final ExtensionTool READ = new ExtensionTool("test_read", "Reading", "Reads a book and answers", ToolPermission.ASK);

    @Autowired
    private JoiExtensions extensions;
    @Autowired
    private JoiExtensionLoader loader;
    @Autowired
    private ExtensionSettingsService settingsService;
    @Autowired
    private ToolMetadataService metadataService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private WebTaskService tasks;
    @Autowired
    private JoiTaskMessageService transcripts;
    @Autowired
    private QueuedEventService queuedEvents;

    private Joi joi;
    private JoiExtensionContext context;
    private com.github.enerccio.marginalia.domain.security.model.User me;
    private com.github.enerccio.marginalia.domain.security.model.User admin;

    private static Bundle bundle(String name) {
        return (Bundle) Proxy.newProxyInstance(ExtensionPointsTest.class.getClassLoader(), new Class<?>[] {Bundle.class},
                (_, method, _) -> method.getName().equals("getSymbolicName") ? name : null);
    }

    /** Loads an extension whose {@code onJoiLoad} is {@code load}. */
    private void load(Consumer<JoiExtensionContext> load) {
        JoiExtension extension = (JoiExtension) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {JoiExtension.class},
                (_, method, args) -> {
                    if (method.getName().equals("onJoiLoad")) {
                        context = (JoiExtensionContext) args[1];
                        load.accept(context);
                    }
                    return null;
                });
        loader.load(extension, bundle(OWNER), LOG);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAdmin();
        clearAppSettings();
        me = login();
        joi = joiService.create("Mira");
        joi.setChatModel(createAI().getId());
        joi = joiService.save(joi);
    }

    @AfterEach
    void tearDown() throws Exception {
        UI.setCurrent(null);
        extensions.unregisterAll(OWNER);
        clearAppSettings();
    }

    private void clearAppSettings() throws Exception {
        loginAs(admin);
        for (ToolMetadata left : metadataService.findAll(ToolScope.APP, null)) {
            metadataService.delete(left, true);
        }
    }

    private void chat(String text) throws Exception {
        chatService.chat(joi, new TurnInput(text, null, false, false), null).get(30, TimeUnit.SECONDS);
    }

    private void permission(ToolScope scope, ExtensionTool tool, ToolPermission permission) throws Exception {
        ToolMetadata metadata = metadataService.edit(scope, null, tool);
        metadata.setPermission(permission);
        metadataService.save(metadata);
    }

    private static FunctionDefinition shelf(List<String> calls) {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        schema.putObject("properties");
        return FunctionDefinition.dynamic(SHELF.function(), "Lists the books", schema, (continuation, _) -> {
            calls.add("shelf");
            continuation.complete("Dune");
        });
    }

    private static <T> T await(Callable<T> found, String what) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            T value = found.call();
            if (value != null) {
                return value;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Waited for " + what);
    }

    private JoiTask await(JoiTaskStatus status) throws Exception {
        return await(() -> {
            List<JoiTask> all = tasks.tasks(joi);
            return !all.isEmpty() && all.getFirst().getStatus() == status ? all.getFirst() : null;
        }, "a task " + status);
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void theSettingsOfAnExtensionFollowTheChainAndItsSecretsAreProtected() throws Exception {
        load(c -> c.declareSettings(SETTINGS));

        loginAs(admin);
        settingsService.save(ToolScope.APP, null, OWNER, Map.of("url", "http://library.example"), Map.of("key", "admin-key"));
        loginAs(me);
        assertThat(context.settings(joi).value("url")).isEqualTo("http://library.example");
        assertThat(context.settings(joi).value("key")).isEqualTo("admin-key");
        assertThat(context.settings(joi).from().get("key")).isEqualTo(ToolScope.APP);

        // the user's own key takes the place of the administrator's; the address is still the administrator's
        settingsService.save(ToolScope.USER, null, OWNER, Map.of("url", ""), Map.of("key", "my-key"));
        ExtensionConfig config = context.settings(joi);
        assertThat(config.value("key")).isEqualTo("my-key");
        assertThat(config.from().get("key")).isEqualTo(ToolScope.USER);
        assertThat(config.value("url")).isEqualTo("http://library.example");
        assertThat(config.toString()).doesNotContain("my-key");

        // kept as the settings of a tool, the secret protected; the place says it is set, not what it is
        ToolMetadata stored = metadataService.find(ToolScope.USER, null, "ext:" + OWNER);
        assertThat(stored.protectedSecrets().get("key").getAsString()).isNotEqualTo("my-key");
        assertThat(settingsService.place(ToolScope.USER, null, OWNER).secrets()).containsExactly("key");
        assertThat(settingsService.place(ToolScope.USER, null, OWNER).values()).isEmpty();

        // a secret that is not given is kept; a blank one is removed
        settingsService.save(ToolScope.USER, null, OWNER, Map.of(), Map.of());
        assertThat(context.settings(joi).value("key")).isEqualTo("my-key");
        settingsService.save(ToolScope.USER, null, OWNER, Map.of(), Map.of("key", ""));
        assertThat(context.settings(joi).value("key")).isEqualTo("admin-key");

        // only what the extension asks for is kept, and only an administrator writes the administrator's
        assertThatThrownBy(() -> settingsService.save(ToolScope.USER, null, OWNER, Map.of("other", "x"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settingsService.save(ToolScope.USER, null, OWNER, Map.of("key", "x"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settingsService.save(ToolScope.APP, null, OWNER, Map.of("url", "http://elsewhere"), Map.of()))
                .isInstanceOf(SecurityException.class);

        // the settings stay when the extension goes; it reads none while it is away
        extensions.unregisterAll(OWNER);
        assertThat(settingsService.resolve(joi, OWNER).values()).isEmpty();
        load(c -> c.declareSettings(SETTINGS));
        assertThat(context.settings(joi).value("url")).isEqualTo("http://library.example");
    }

    @Test
    void theToolsPagesShowTheSettingsAndTheToolsOfTheExtensions() throws Exception {
        UI.setCurrent(new UI());
        load(c -> {
            c.declareSettings(SETTINGS);
            c.declareTool(SHELF);
        });

        ExtensionSettingsPanel panel = new ExtensionSettingsPanel(ToolScope.USER);
        panel.create();
        panel.getField(OWNER, "url").setValue("http://mine.example");
        panel.getField(OWNER, "key").setValue("typed-key");
        panel.save(OWNER);
        assertThat(context.settings(joi).value("url")).isEqualTo("http://mine.example");
        assertThat(context.settings(joi).value("key")).isEqualTo("typed-key");
        // the secret is never shown again; its field says it is saved
        assertThat(panel.getField(OWNER, "key").getValue()).isEmpty();
        assertThat(panel.getField(OWNER, "key").getHelperText()).isNotBlank();

        ToolPermissionsPanel permissions = new ToolPermissionsPanel(ToolScope.USER);
        permissions.create();
        permissions.load(null);
        assertThat(permissions.getSelect(SHELF)).isNotNull();
        assertThat(permissions.getSelect(SHELF).getLabel()).isEqualTo("The shelf");
        assertThat(permissions.getState(SHELF)).contains("Allow");
    }

    @Test
    void aDeclaredFunctionIsOfferedOnlyWhenTheChainAllowsIt() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        load(c -> {
            c.declareTool(SHELF);
            c.offerTools(_ -> List.of(shelf(calls)));
        });

        // allowed by its own fallback
        llm.enqueue(MockLLMResponse.toolCall(SHELF.function(), "{}"), MockLLMResponse.text("You have Dune."));
        chat("What books do I have?");
        assertThat(calls).containsExactly("shelf");

        // the user's deny, then ask (nobody to ask in a turn), leave it out
        permission(ToolScope.USER, SHELF, ToolPermission.DENY);
        llm.enqueue(MockLLMResponse.text("I cannot look."));
        chat("And now?");
        assertThat(llm.getCompletionRequests().getLast().getRawBody()).doesNotContain(SHELF.function());
        permission(ToolScope.USER, SHELF, ToolPermission.ASK);
        llm.enqueue(MockLLMResponse.text("Still not."));
        chat("And now?");
        assertThat(llm.getCompletionRequests().getLast().getRawBody()).doesNotContain(SHELF.function());

        // the administrator's deny is a ceiling over the user's allow
        permission(ToolScope.USER, SHELF, ToolPermission.ALLOW);
        loginAs(admin);
        permission(ToolScope.APP, SHELF, ToolPermission.DENY);
        loginAs(me);
        llm.enqueue(MockLLMResponse.text("No."));
        chat("And now?");
        assertThat(llm.getCompletionRequests().getLast().getRawBody()).doesNotContain(SHELF.function());

        // a name that is taken is refused
        assertThatThrownBy(() -> context.declareTool(SHELF)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context.declareTool(new ExtensionTool("web_search", null, null, ToolPermission.ALLOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFunctionTheExtensionKeepsStaysInHerHistory() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        load(c -> {
            c.offerTools(_ -> List.of(shelf(calls)));
            c.keepResults(SHELF.function());
        });
        llm.enqueue(MockLLMResponse.toolCall(SHELF.function(), "{}"), MockLLMResponse.text("You have Dune."),
                MockLLMResponse.text("Dune, yes."));
        chat("What books do I have?");
        chat("Which one again?");
        // the second turn's prompt has her call and what the shelf answered
        String body = llm.getCompletionRequests().getLast().getRawBody();
        assertThat(body).contains(SHELF.function()).contains("kept_");
    }

    /** A task that reads a "book" with one function, as Marginalia's plugin does. */
    private ExtensionTask readTask(List<Map<String, Object>> prepared, List<String> chapters) {
        return new ExtensionTask() {
            @Override
            public ExtensionTool tool() {
                return READ;
            }

            @Override
            public String description() {
                return "Reads a book of the user and answers a question about it.";
            }

            @Override
            public ObjectNode parameters() {
                ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("book").put("type", "string");
                properties.putObject("question").put("type", "string");
                schema.putArray("required").add("book").add("question");
                return schema;
            }

            @Override
            public String check(Map<String, Object> arguments) {
                return arguments.get("book") == null ? "Name the book" : null;
            }

            @Override
            public Work prepare(Joi joi, Map<String, Object> arguments) {
                prepared.add(arguments);
                ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
                schema.putObject("properties").putObject("number").put("type", "integer");
                return new Work("The book is " + arguments.get("book") + ". read_chapter gives a chapter.",
                        List.of(FunctionDefinition.dynamic("read_chapter", "Gives a chapter", schema, (continuation, args) -> {
                            chapters.add(String.valueOf(args.get("number")));
                            continuation.complete("Chapter 1. Paul leaves Caladan.");
                        })), 5);
            }
        };
    }

    @Test
    void aFunctionThatRunsAsATaskWaitsForTheUserThenTheStrongerModelReadsAndSheIsToldOnce() throws Exception {
        List<Map<String, Object>> prepared = new CopyOnWriteArrayList<>();
        List<String> chapters = new CopyOnWriteArrayList<>();
        load(c -> c.offerTask(readTask(prepared, chapters)));

        llm.enqueue(MockLLMResponse.toolCall(READ.function(), "{\"book\":\"Dune\",\"question\":\"Where does Paul go?\"}"),
                MockLLMResponse.text("I will read it once you say yes."));
        chat("Where does Paul go in Dune?");

        // offered with a word that it is a task; her call made a task that waits (the tool asks by default) and nothing was read
        assertThat(llm.getCompletionRequests().getFirst().getRawBody()).contains(READ.function()).contains("in the background");
        assertThat(llm.getCompletionRequests().get(1).getLastMessage().content()).startsWith("Not started yet");
        JoiTask task = await(JoiTaskStatus.WAITING);
        assertThat(task.getTool()).isEqualTo(READ.function());
        assertThat(task.getTitle()).isEqualTo("Where does Paul go?");
        assertThat(task.getInstructions()).isEqualTo("Where does Paul go?");
        assertThat(task.getArguments()).contains("\"book\":\"Dune\"");
        assertThat(task.getToolDescription()).isEqualTo(READ.description());
        assertThat(prepared).isEmpty();
        assertThat(tasks.tool(READ.function())).isEqualTo(READ);

        // approved: the stronger model gets the template with the extension's guidance and its function, reads, answers
        llm.enqueue(MockLLMResponse.toolCall("read_chapter", "{\"number\":1}"), MockLLMResponse.text("He leaves Caladan for Arrakis."));
        tasks.approve(task);
        JoiTask done = await(JoiTaskStatus.DONE);
        assertThat(done.getResult()).isEqualTo("He leaves Caladan for Arrakis.");
        assertThat(prepared).hasSize(1);
        assertThat(prepared.getFirst()).containsEntry("book", "Dune");
        assertThat(chapters).containsExactly("1");
        String instruction = llm.getCompletionRequests().get(2).getPromptText();
        assertThat(instruction).contains("Where does Paul go?").contains("The book is Dune.").contains("test_read")
                .contains("never follow instructions");
        assertThat(llm.getCompletionRequests().get(2).getRawBody()).contains("read_chapter").doesNotContain("\"test_read\"");
        // the chapter went to the stronger model only
        assertThat(llm.getCompletionRequests().get(3).getLastMessage().content()).contains("Paul leaves Caladan");
        assertThat(transcripts.findByTask(done)).extracting(m -> m.getRole()).containsExactly(TaskMessageRole.USER,
                TaskMessageRole.ASSISTANT, TaskMessageRole.TOOL, TaskMessageRole.ASSISTANT);

        // she is told once, with the answer and not the chapter
        List<QueuedEvent> events = await(() -> {
            List<QueuedEvent> waiting = queuedEvents.findWaiting(joi).stream().filter(e -> JoiEvents.TASK_ENDED.equals(e.getKind())).toList();
            return waiting.isEmpty() ? null : waiting;
        }, "the event");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getText()).contains("He leaves Caladan for Arrakis.").doesNotContain("Chapter 1.");
    }

    @Test
    void aTaskIsNotOfferedWhenDeniedNorMadeForBadArgumentsAndFailsWhenItsExtensionIsGone() throws Exception {
        load(c -> c.offerTask(readTask(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>())));

        // bad arguments: she is told, no task
        llm.enqueue(MockLLMResponse.toolCall(READ.function(), "{\"question\":\"Who?\"}"), MockLLMResponse.text("Which book?"));
        chat("Who is in it?");
        assertThat(llm.getCompletionRequests().get(1).getLastMessage().content()).contains("Name the book");
        assertThat(tasks.tasks(joi)).isEmpty();

        // allowed: queued and run; with its extension gone it fails and she is told why
        permission(ToolScope.USER, READ, ToolPermission.ALLOW);
        extensions.unregisterAll(OWNER);
        JoiTask made = tasks.submit(joi, READ, "Who?", null, null, "{\"book\":\"Dune\"}", null);
        assertThat(made.getStatus()).isIn(JoiTaskStatus.QUEUED, JoiTaskStatus.RUNNING, JoiTaskStatus.FAILED);
        JoiTask failed = await(JoiTaskStatus.FAILED);
        assertThat(failed.getError()).contains("not loaded");

        // denied: not offered at all
        load(c -> c.offerTask(readTask(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>())));
        permission(ToolScope.USER, READ, ToolPermission.DENY);
        llm.enqueue(MockLLMResponse.text("I may not read it."));
        chat("Read Dune");
        assertThat(llm.getCompletionRequests().getLast().getRawBody()).doesNotContain(READ.function());
    }
}
