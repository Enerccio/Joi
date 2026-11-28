package com.github.enerccio.joi.extensions.marginalia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plugin's client against a fake Marginalia API: no network but localhost. */
class MarginaliaClientTest {

    private FakeMarginalia marginalia;
    private MarginaliaClient client;

    @BeforeEach
    void setUp() throws Exception {
        marginalia = new FakeMarginalia();
        client = new MarginaliaClient(marginalia.url(), FakeMarginalia.KEY);
    }

    @AfterEach
    void tearDown() {
        marginalia.close();
    }

    @Test
    void theAddressTakesApiV1OnceAndOnlyHttp() {
        assertEquals("https://m.example/api/v1", MarginaliaClient.apiBase("https://m.example"));
        assertEquals("https://m.example/api/v1", MarginaliaClient.apiBase("https://m.example/"));
        assertEquals("https://m.example/x/api/v1", MarginaliaClient.apiBase(" https://m.example/x/api/v1/ "));
        assertThrows(IllegalArgumentException.class, () -> MarginaliaClient.apiBase("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> MarginaliaClient.apiBase(""));
        assertThrows(IllegalArgumentException.class, () -> new MarginaliaClient("https://m.example", " "));
    }

    @Test
    void theBooksAreListedAndFoundByIdNameOrPartOfIt() throws Exception {
        List<MarginaliaClient.Book> books = client.books();
        assertEquals(2, books.size());
        assertEquals(new MarginaliaClient.Book("b-1", "The Salt Road", "Writing/Novels", false), books.getFirst());
        // the default list is asked without ?all: a book kept out of it is not there
        assertEquals("books", marginalia.requests().getLast().path());
        List<MarginaliaClient.Book> all = client.books(true);
        assertEquals("books?all=true", marginalia.requests().getLast().path());
        assertEquals(new MarginaliaClient.Book("b-3", "Old Draft", null, true), all.getLast());
        // she still finds it when she names it
        assertEquals("b-3", client.book("old draft").uuid());
        assertEquals("b-3", client.book("b-3").uuid());
        assertEquals("b-1", client.book("b-1").uuid());
        assertEquals("b-1", client.book("the salt road").uuid());
        assertEquals("b-2", client.book("Ash").uuid());
        MarginaliaException ambiguous = assertThrows(MarginaliaException.class, () -> client.book("Salt"));
        assertTrue(ambiguous.getMessage().contains("Several books"), ambiguous.getMessage());
        MarginaliaException none = assertThrows(MarginaliaException.class, () -> client.book("Dune"));
        assertTrue(none.getMessage().contains("The Salt Road"), none.getMessage());
        // the key goes in the header only
        assertTrue(marginalia.requests().stream().noneMatch(r -> r.path().contains(FakeMarginalia.KEY) || r.body().contains(FakeMarginalia.KEY)));
    }

    @Test
    void onlyTheCommandsThatReadAreListedWithTheirSchemasFromTheOpenApi() throws Exception {
        List<MarginaliaClient.Command> commands = client.readOnlyCommands();
        assertEquals(List.of("get-outline", "read-chapter", "search-parts"), commands.stream().map(MarginaliaClient.Command::name).toList());
        // not in the OpenAPI description: made from its two forms, as the description would have them
        com.google.gson.JsonObject search = commands.get(2).arguments();
        assertEquals(2, search.getAsJsonArray("oneOf").size());
        assertEquals("integer", search.getAsJsonArray("oneOf").get(1).getAsJsonObject().getAsJsonObject("properties")
                .getAsJsonObject("limit").get("type").getAsString());
        MarginaliaClient.Command read = commands.get(1);
        assertEquals("Gives the text of a chapter", read.description());
        assertEquals("integer", read.arguments().getAsJsonObject("properties").getAsJsonObject("number").get("type").getAsString());
        assertEquals(1, read.arguments().getAsJsonObject("properties").getAsJsonObject("number").get("minimum").getAsInt());
    }

    @Test
    void aCommandIsRunOnTheBookAndARunIsFollowedUntilItIsDone() throws Exception {
        assertEquals("1. Departure\n2. The Ford", client.run("b-1", "get-outline", Map.of(), () -> false, Duration.ofSeconds(30)));
        String chapter = client.run("b-1", "read-chapter", Map.of("number", 1), () -> false, Duration.ofSeconds(30));
        assertEquals("Chapter 1. Mara leaves the coast with the salt.", chapter);
        FakeMarginalia.Request call = marginalia.requests().stream().filter(r -> r.path().endsWith("read-chapter")).findFirst().orElseThrow();
        assertEquals("POST", call.method());
        assertTrue(call.body().contains("\"arguments\":{\"number\":1}"), call.body());
        assertTrue(call.body().contains("\"wait\":30"), call.body());
        assertTrue(marginalia.requests().stream().filter(r -> r.path().equals("runs/r1")).count() >= 2);
    }

    @Test
    void aLongResultIsCutAndAnErrorSaysWhatMarginaliaSaid() throws Exception {
        marginalia.chapter = "x".repeat(MarginaliaClient.MAX_RESULT + 100);
        String chapter = client.run("b-1", "read-chapter", Map.of("number", 1), () -> false, Duration.ofSeconds(30));
        assertTrue(chapter.length() < MarginaliaClient.MAX_RESULT + 100);
        assertTrue(chapter.endsWith("ask for a smaller part]"));

        MarginaliaException forbidden = assertThrows(MarginaliaException.class,
                () -> client.run("b-1", "write-chapter", Map.of("text", "no"), () -> false, Duration.ofSeconds(5)));
        assertEquals(403, forbidden.status());
        assertTrue(forbidden.getMessage().contains("READ"), forbidden.getMessage());

        MarginaliaException wrongKey = assertThrows(MarginaliaException.class, () -> new MarginaliaClient(marginalia.url(), "mrg_wrong").books());
        assertEquals(401, wrongKey.status());
        assertTrue(!wrongKey.getMessage().contains("mrg_wrong"));
    }
}
