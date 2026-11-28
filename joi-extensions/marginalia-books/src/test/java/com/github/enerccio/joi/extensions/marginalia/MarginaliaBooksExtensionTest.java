package com.github.enerccio.joi.extensions.marginalia;

import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.extensions.ExtensionTask;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.extensions.JoiExtensionContext;
import com.github.enerccio.joi.extensions.ToolProvider;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the extension registers in Joi and what its functions do, with a fake context of Joi and a fake Marginalia. */
class MarginaliaBooksExtensionTest {

    private FakeMarginalia marginalia;
    private volatile ExtensionConfig config = ExtensionConfig.empty();
    private final List<ExtensionSettings> settings = new CopyOnWriteArrayList<>();
    private final List<ExtensionTool> tools = new CopyOnWriteArrayList<>();
    private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();
    private final List<ExtensionTask> tasks = new CopyOnWriteArrayList<>();
    private final List<String> kept = new CopyOnWriteArrayList<>();

    /** The part of Joi's context the extension uses. */
    private JoiExtensionContext context() {
        return (JoiExtensionContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {JoiExtensionContext.class},
                (_, method, args) -> switch (method.getName()) {
                    case "owner" -> "marginalia-books";
                    case "log" -> LoggerFactory.getLogger("extension.marginalia-books");
                    case "settings" -> config;
                    case "declareSettings" -> settings.add((ExtensionSettings) args[0]) ? null : null;
                    case "declareTool" -> tools.add((ExtensionTool) args[0]) ? null : null;
                    case "offerTools" -> providers.add((ToolProvider) args[0]) ? null : null;
                    case "offerTask" -> tasks.add((ExtensionTask) args[0]) ? null : null;
                    case "keepResults" -> kept.addAll(List.of((String[]) args[0])) ? null : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @BeforeEach
    void setUp() throws Exception {
        marginalia = new FakeMarginalia();
        new MarginaliaBooksExtension().onJoiLoad(null, context());
    }

    @AfterEach
    void tearDown() {
        marginalia.close();
    }

    private void configure() {
        config = new ExtensionConfig(Map.of(MarginaliaBooksExtension.URL, marginalia.url(), MarginaliaBooksExtension.API_KEY, FakeMarginalia.KEY),
                Map.of(MarginaliaBooksExtension.URL, ToolScope.APP, MarginaliaBooksExtension.API_KEY, ToolScope.USER));
    }

    private static String call(FunctionDefinition definition, String arguments) throws Exception {
        return FunctionSet.bind(List.of(definition), definition).call(definition.getName(), arguments, Executors.newVirtualThreadPerTaskExecutor(),
                () -> false).get(30, TimeUnit.SECONDS);
    }

    @Test
    void itAsksForTheAddressAndAKeyAndDeclaresItsTools() {
        assertEquals(1, settings.size());
        assertNotNull(settings.getFirst().field("url"));
        assertFalse(settings.getFirst().field("url").secret());
        assertTrue(settings.getFirst().field("apiKey").secret());
        // listing runs in her turn and is allowed by default; reading is a task that asks
        assertEquals(List.of("marginalia_books", "marginalia_read_part"), tools.stream().map(ExtensionTool::function).toList());
        assertTrue(tools.stream().allMatch(tool -> tool.fallback() == ToolPermission.ALLOW));
        assertEquals(1, tasks.size());
        assertEquals("marginalia_read_book", tasks.getFirst().tool().function());
        assertEquals(ToolPermission.ASK, tasks.getFirst().tool().fallback());
        // what she read (the list, a part) stays in her history
        assertEquals(List.of("marginalia_books", "marginalia_read_part"), kept);
    }

    @Test
    void nothingIsOfferedUntilMarginaliaIsSetUpThenTheBooksAreListed() throws Exception {
        assertTrue(providers.getFirst().tools(null).isEmpty());
        assertFalse(tasks.getFirst().offered(null));

        configure();
        assertTrue(tasks.getFirst().offered(null));
        List<FunctionDefinition> offered = providers.getFirst().tools(null);
        assertEquals(List.of("marginalia_books", "marginalia_read_part"), offered.stream().map(FunctionDefinition::getName).toList());
        String books = call(offered.getFirst(), "{}");
        assertTrue(books.contains("The Salt Road — Writing/Novels — b-1"), books);
        assertTrue(books.contains("Salt and Ash — (no folder) — b-2"), books);
        assertFalse(books.contains("Old Draft"), books);
        String all = call(offered.getFirst(), "{\"all\":true}");
        assertTrue(all.contains("Old Draft — (no folder) — b-3 (kept out of the list)"), all);
    }

    @Test
    void sheListsThePartsAndReadsOneHerselfAPageAtATime() throws Exception {
        configure();
        FunctionDefinition read = providers.getFirst().tools(null).get(1);
        assertEquals("[\"book\"]", read.getParametersSchema().path("required").toString());

        String parts = call(read, "{\"book\":\"salt road\"}");
        assertTrue(parts.contains("h-2 The ford"), parts);
        assertEquals("{\"arguments\":{},\"wait\":30}", last("list-parts").body());

        assertEquals("Mara loads the salt.", call(read, "{\"book\":\"b-1\",\"part\":\"h-1\"}"));
        assertEquals("{\"arguments\":{\"part\":\"h-1\"},\"wait\":30}", last("get-part").body());
        // a page further on: the paged form, the active branch, never more than a page
        call(read, "{\"book\":\"b-1\",\"part\":\"2\",\"offset\":6000,\"length\":99999}");
        assertEquals("{\"arguments\":{\"part\":\"2\",\"branch\":\"\",\"offset\":6000,\"length\":6000},\"wait\":30}",
                last("get-part").body());

        assertThrows(Exception.class, () -> call(read, "{}"));
        assertThrows(Exception.class, () -> call(read, "{\"book\":\"Dune\"}"));
    }

    private FakeMarginalia.Request last(String command) {
        return marginalia.requests().reversed().stream().filter(r -> r.path().endsWith("/commands/" + command)).findFirst().orElseThrow();
    }

    @Test
    void readingABookGivesTheStrongerModelTheReadOnlyCommandsOnThatBook() throws Exception {
        ExtensionTask task = tasks.getFirst();
        assertNotNull(task.check(Map.of("question", "What happens?")));
        assertNotNull(task.check(Map.of("book", "The Salt Road")));
        assertNull(task.check(Map.of("book", "The Salt Road", "question", "Where does Mara go?")));
        assertEquals("The Salt Road: Where does Mara go?", task.title(Map.of("book", "The Salt Road", "question", "Where does Mara go?")));

        configure();
        ExtensionTask.Work work = task.prepare(null, Map.of("book", "salt road", "question", "Where does Mara go?"));
        assertEquals(List.of("get-outline", "read-chapter", "search-parts"), work.functions().stream().map(FunctionDefinition::getName).toList());
        assertEquals("[\"query\"]", work.functions().get(2).getParametersSchema().path("required").toString());
        assertTrue(work.guidance().contains("\"The Salt Road\" (folder Writing/Novels)"), work.guidance());
        assertTrue(work.guidance().contains("- read-chapter: Read a chapter"), work.guidance());
        assertEquals(ReadBookTask.MAX_CALLS, work.maxCalls());
        FunctionDefinition read = work.functions().get(1);
        assertEquals("integer", read.getParametersSchema().path("properties").path("number").path("type").asText());

        // the function runs the command on the book she named, and its run is followed
        assertEquals("Chapter 1. Mara leaves the coast with the salt.", call(read, "{\"number\":1}"));
        assertTrue(marginalia.requests().stream().anyMatch(r -> r.path().equals("books/b-1/commands/read-chapter")));
        // nothing that writes was offered or run
        assertTrue(marginalia.requests().stream().noneMatch(r -> r.path().contains("write-chapter")));
    }

    @Test
    void aBookThatIsNotThereOrMarginaliaNotSetUpFailsTheTaskWithTheReason() {
        ExtensionTask task = tasks.getFirst();
        IllegalStateException notSetUp = assertThrows(IllegalStateException.class, () -> task.prepare(null, Map.of("book", "x", "question", "y")));
        assertTrue(notSetUp.getMessage().contains("User Settings"));
        configure();
        MarginaliaException missing = assertThrows(MarginaliaException.class, () -> task.prepare(null, Map.of("book", "Dune", "question", "y")));
        assertTrue(missing.getMessage().contains("No book"));
    }

    @Test
    void functionNamesAreWhatAModelTakesAndUnique() throws Exception {
        Set<String> taken = new HashSet<>();
        assertEquals("get-outline", ReadBookTask.functionName("get-outline", taken));
        assertEquals("get-outline_2", ReadBookTask.functionName("get-outline", taken));
        assertEquals("a_b_c", ReadBookTask.functionName("a.b c", taken));
        assertTrue(ReadBookTask.functionName("x".repeat(100), taken).length() <= 64);
        // several forms (as get-part has): one object with every name, required what every form requires
        com.google.gson.JsonObject forms = com.google.gson.JsonParser.parseString("""
                {"oneOf":[{"type":"object","properties":{"part":{"type":"string"}},"required":["part"]},
                          {"type":"object","properties":{"part":{"type":"string"},"length":{"type":"integer"}},"required":["part","length"]}]}""")
                .getAsJsonObject();
        com.fasterxml.jackson.databind.node.ObjectNode merged = ReadBookTask.schema(forms);
        assertEquals("object", merged.path("type").asText());
        assertEquals("integer", merged.path("properties").path("length").path("type").asText());
        assertEquals("[\"part\"]", merged.path("required").toString());
        assertTrue(merged.path("description").asText().contains("(part) or (part, length)"), merged.toString());
        assertEquals("object", ReadBookTask.schema(null).path("type").asText());
    }
}
