package com.github.enerccio.joi.domain.web;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each source against a fake server: the request it makes (address, key, parameters, the sites), what it makes of the
 * answer, and how it fails. No real service is called.
 */
class WebSearchClientTest {

    private static WebSearchConfig config(int maxResults, boolean safe, String language) {
        return new WebSearchConfig(List.of(), null, 5, maxResults, language, safe, null, false, 0, 0);
    }

    private static SearchSource source(SearchSourceType type, String url, List<String> sites) {
        return new SearchSource(type.name().toLowerCase(), type.name(), type, true, false, url, "engine-1", sites, null);
    }

    private static String decoded(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    @Test
    void braveAsksWithItsTokenAndTheSitesInTheQuery() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"query":{"original":"rust"},"web":{"results":[
                  {"title":"Rust","url":"https://rust-lang.org","description":"A language"},
                  {"title":"Book","url":"https://doc.rust-lang.org/book","description":"The   book\\nof Rust"}]}}"""))) {
            SearchSource brave = source(SearchSourceType.BRAVE, web.url() + "/res/v1/web/search", List.of("rust-lang.org", "docs.rs"));
            SearchResult result = new WebSearchClient(config(30, true, "en")).search(brave, "token-1", null, "rust traits");

            FakeWeb.Seen seen = web.last();
            assertThat(seen.method()).isEqualTo("GET");
            assertThat(seen.header("X-Subscription-Token")).isEqualTo("token-1");
            String uri = decoded(seen.uri());
            assertThat(uri).contains("q=rust traits (site:rust-lang.org OR site:docs.rs)").contains("count=20")
                    .contains("safesearch=strict").contains("search_lang=en");
            assertThat(result.hits()).extracting(SearchHit::title).containsExactly("Rust", "Book");
            assertThat(result.hits().get(1).snippet()).isEqualTo("The book of Rust");
            assertThat(result.hits().get(1).source()).isEqualTo("BRAVE");
            assertThat(result.answer()).isNull();
        }
    }

    @Test
    void searxngNeedsItsAddressAndSaysWhenJsonIsOff() throws Exception {
        assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.SEARXNG, null, List.of()), null, null, "x"))
                .isInstanceOf(WebException.class).hasMessageContaining("address of the SearXNG");

        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"results":[{"title":"A","url":"https://a.example","content":"about a","engine":"x"},
                            {"title":"B","url":"https://b.example","content":"about b"}]}"""))) {
            SearchResult result = new WebSearchClient(config(1, false, null)).search(source(SearchSourceType.SEARXNG, web.url() + "/", List.of()), null, null, "a b");
            assertThat(decoded(web.last().uri())).startsWith("/search?format=json&q=a b").contains("safesearch=0");
            assertThat(result.hits()).hasSize(1);
            assertThat(result.hits().getFirst().url()).isEqualTo("https://a.example");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(403, "text/html", "<html>Forbidden</html>"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.SEARXNG, web.url(), List.of()), null, null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("does not allow the json format");
        }
    }

    @Test
    void googleUsesSiteSearchForOneSiteAndTheQueryForMore() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"kind":"customsearch#search","items":[{"title":"T","link":"https://t.example","snippet":"s"}]}"""))) {
            WebSearchClient client = new WebSearchClient(config(25, true, "cs"));
            SearchResult result = client.search(source(SearchSourceType.GOOGLE, web.url() + "/customsearch/v1", List.of("x.example")), "key-1", null, "hello");
            String uri = decoded(web.last().uri());
            assertThat(uri).contains("key=key-1").contains("cx=engine-1").contains("q=hello&").contains("num=10").contains("safe=active")
                    .contains("siteSearch=x.example").contains("siteSearchFilter=i").contains("hl=cs").contains("lr=lang_cs");
            assertThat(result.hits()).extracting(SearchHit::url).containsExactly("https://t.example");

            client.search(source(SearchSourceType.GOOGLE, web.url(), List.of("x.example", "y.example")), "key-1", null, "hello");
            uri = decoded(web.last().uri());
            assertThat(uri).contains("q=hello (site:x.example OR site:y.example)").doesNotContain("siteSearch");
        }
        // the engine id is not optional
        SearchSource noEngine = new SearchSource("g", "G", SearchSourceType.GOOGLE, true, false, "http://127.0.0.1:1", null, List.of(), null);
        assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(noEngine, "k", null, "x"))
                .isInstanceOf(WebException.class).hasMessageContaining("engine id");
    }

    @Test
    void tavilyGivesItsAnswerAndRestrictsByDomain() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"query":"q","answer":"Forty-two.","results":[{"title":"T","url":"https://t.example","content":"c","score":0.9}]}"""))) {
            SearchResult result = new WebSearchClient(config(50, true, "en")).search(
                    source(SearchSourceType.TAVILY, web.url() + "/search", List.of("t.example")), "tvly-1", null, "meaning");
            FakeWeb.Seen seen = web.last();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.header("Authorization")).isEqualTo("Bearer tvly-1");
            assertThat(seen.body()).contains("\"query\":\"meaning\"").contains("\"max_results\":20").contains("\"include_domains\":[\"t.example\"]")
                    .contains("\"include_answer\":\"basic\"").contains("\"safe_search\":true").contains("\"language\":\"en\"");
            assertThat(result.answer()).isEqualTo("Forty-two.");
            assertThat(result.hits()).hasSize(1);
        }
    }

    @Test
    void perplexityFiltersDomainsAndLanguages() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"id":"1","results":[{"title":"P","url":"https://p.example","snippet":"text","date":"2026-01-01"}]}"""))) {
            SearchResult result = new WebSearchClient(config(8, false, "de")).search(
                    source(SearchSourceType.PERPLEXITY, web.url() + "/search", List.of("p.example")), "pplx-1", null, "q");
            FakeWeb.Seen seen = web.last();
            assertThat(seen.header("Authorization")).isEqualTo("Bearer pplx-1");
            assertThat(seen.body()).contains("\"search_domain_filter\":[\"p.example\"]").contains("\"search_language_filter\":[\"de\"]").contains("\"max_results\":8");
            assertThat(result.hits().getFirst().snippet()).isEqualTo("text");
        }
    }

    @Test
    void duckDuckGoIsReadFromItsHtmlPage() throws Exception {
        String page = """
                <html><body>
                <div class="result result--ad"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fad.example">Ad</a></div>
                <div class="result"><h2><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.example.org%2Fa%3Fx%3D1&rut=abc">First  hit</a></h2>
                  <a class="result__snippet">The   first
                  snippet</a></div>
                <div class="result"><a class="result__a" href="https://direct.example/b">Second</a></div>
                </body></html>""";
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html(page))) {
            SearchResult result = new WebSearchClient(config(8, true, null)).search(
                    source(SearchSourceType.DDGS, web.url() + "/html/", List.of("example.org")), null, null, "first");
            FakeWeb.Seen seen = web.last();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
            assertThat(decoded(seen.body())).contains("q=first site:example.org").contains("kp=1");
            assertThat(result.hits()).extracting(SearchHit::title).containsExactly("First hit", "Second");
            assertThat(result.hits().getFirst().url()).isEqualTo("https://www.example.org/a?x=1");
            assertThat(result.hits().getFirst().snippet()).isEqualTo("The first snippet");
        }
        // a page without results is no results when it says so, and an error when it is something else (a captcha)
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html("<html><body><div class=\"no-results\">None.</div></body></html>"))) {
            assertThat(new WebSearchClient(config(8, false, null)).search(source(SearchSourceType.DDGS, web.url(), List.of()), null, null, "x").hits()).isEmpty();
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html("<html><body><form>Are you a robot?</form></body></html>"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(8, false, null)).search(source(SearchSourceType.DDGS, web.url(), List.of()), null, null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("captcha");
        }
    }

    @Test
    void aSourceThatNeedsAKeyWithoutOneDoesNotCallAnything() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{}"))) {
            for (SearchSourceType type : List.of(SearchSourceType.BRAVE, SearchSourceType.TAVILY, SearchSourceType.PERPLEXITY, SearchSourceType.GOOGLE)) {
                assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(type, web.url(), List.of()), " ", null, "x"))
                        .isInstanceOf(WebException.class).hasMessageContaining("no API key");
            }
            assertThat(web.seen).isEmpty();
        }
        assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.DDGS, "http://127.0.0.1:1", List.of()), null, null, " "))
                .isInstanceOf(WebException.class).hasMessageContaining("nothing to search");
    }

    @Test
    void aFailureSaysWhyAndNeverShowsTheKey() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(429, "application/json", "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"x\",\"code\":429}}"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.PERPLEXITY, web.url(), List.of()), "pplx-secret-1", null, "x"))
                    .isInstanceOfSatisfying(WebException.class, e -> {
                        assertThat(e.status()).isEqualTo(429);
                        assertThat(e.getMessage()).contains("429").contains("Rate limit reached").doesNotContain("pplx-secret-1");
                    });
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(401, "application/json", "{\"detail\":{\"error\":\"Unauthorized: missing or invalid API key.\"}}"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.TAVILY, web.url(), List.of()), "k", null, "x"))
                    .hasMessageContaining("Unauthorized: missing or invalid API key.");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html("<html>Not what was asked</html>"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(5, false, null)).search(source(SearchSourceType.BRAVE, web.url(), List.of()), "k", null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("did not answer with JSON");
        }
    }

    @Test
    void aSlowSourceTimesOut() throws Exception {
        try (FakeWeb web = new FakeWeb(new FakeWeb.Reply(200, "application/json", "{}", java.util.Map.of(), 3000))) {
            WebSearchConfig fast = new WebSearchConfig(List.of(), null, 1, 5, null, false, null, false, 0, 0);
            assertThatThrownBy(() -> new WebSearchClient(fast).search(source(SearchSourceType.BRAVE, web.url(), List.of()), "k", null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("Could not reach");
        }
    }

    @Test
    void theProxyIsUsedAndGetsTheCredentialsWhenItAsks() throws Exception {
        // a proxy gets the whole address of a plain http request; this one asks for a password first
        try (FakeWeb proxy = new FakeWeb(request -> request.header("Proxy-Authorization") == null
                ? new FakeWeb.Reply(407, "text/plain", "login", java.util.Map.of("Proxy-Authenticate", "Basic realm=\"joi\""), 0)
                : FakeWeb.Reply.json("{\"web\":{\"results\":[{\"title\":\"Via proxy\",\"url\":\"https://p.example\",\"description\":\"d\"}]}}"))) {
            WebSearchConfig config = new WebSearchConfig(List.of(), proxy.url(), 5, 5, null, false, null, false, 0, 0);
            SearchResult result = new WebSearchClient(config).search(source(SearchSourceType.BRAVE, "http://search.invalid:8080/res/v1/web/search", List.of()),
                    "k", "user:pa:ss", "x");
            assertThat(result.hits()).extracting(SearchHit::title).containsExactly("Via proxy");
            FakeWeb.Seen last = proxy.last();
            assertThat(last.uri()).startsWith("http://search.invalid:8080/res/v1/web/search");
            assertThat(last.header("Proxy-Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("user:pa:ss".getBytes(StandardCharsets.UTF_8)));
        }
        // a proxy that is not an HTTP one is refused, not guessed
        WebSearchConfig socks = new WebSearchConfig(List.of(), "socks5://127.0.0.1:1080", 5, 5, null, false, null, false, 0, 0);
        assertThatThrownBy(() -> new WebSearchClient(socks).search(source(SearchSourceType.BRAVE, "http://127.0.0.1:1", List.of()), "k", null, "x"))
                .isInstanceOf(WebException.class).hasMessageContaining("HTTP proxy");
    }

    @Test
    void withoutLeaveForTheServersNetworkNeitherASourceNorAProxyOnItIsCalled() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"results\":[]}"))) {
            assertThatThrownBy(() -> new WebSearchClient(config(5, false, null), false).search(source(SearchSourceType.SEARXNG, web.url(), List.of()), null, null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("own network");
            // a proxy on it neither
            WebSearchConfig proxied = new WebSearchConfig(List.of(), web.url(), 5, 5, null, false, null, false, 0, 0);
            assertThatThrownBy(() -> new WebSearchClient(proxied, false).search(source(SearchSourceType.BRAVE, "http://search.invalid/", List.of()), "k", null, "x"))
                    .isInstanceOf(WebException.class).hasMessageContaining("own network");
            assertThat(web.seen).isEmpty();
            // with leave, the same source answers
            assertThat(new WebSearchClient(config(5, false, null), true).search(source(SearchSourceType.SEARXNG, web.url(), List.of()), null, null, "x").hits()).isEmpty();
        }
    }

    @Test
    void theConfigIsReadWithItsDefaultsAndLimits() {
        WebSearchConfig empty = WebSearchConfig.from(null);
        assertThat(empty.timeoutSeconds()).isEqualTo(WebSearchConfig.TIMEOUT);
        assertThat(empty.maxResults()).isEqualTo(WebSearchConfig.RESULTS);
        assertThat(empty.maxSearches()).isEqualTo(WebSearchConfig.SEARCHES);
        assertThat(empty.maxPages()).isEqualTo(WebSearchConfig.PAGES);
        assertThat(empty.sources()).isEmpty();

        WebSearchConfig kept = new WebSearchConfig(List.of(source(SearchSourceType.BRAVE, null, List.of("a.example")),
                new SearchSource("off", "Off", SearchSourceType.DDGS, false, true, null, null, null, null),
                new SearchSource("home", "Home", SearchSourceType.SEARXNG, true, true, "http://searx.lan", null, null, null)),
                "http://proxy:3128", 0, 500, "en", true, null, false, 99, 3);
        WebSearchConfig again = WebSearchConfig.from(kept.toJson());
        assertThat(again).isEqualTo(kept);
        assertThat(again.maxResults()).isEqualTo(50);
        assertThat(again.maxSearches()).isEqualTo(50);
        assertThat(again.usable()).extracting(SearchSource::id).containsExactly("brave", "home");
        assertThat(new WebSearchConfig(kept.sources(), null, 0, 0, null, false, null, true, 0, 0).usable()).extracting(SearchSource::id).containsExactly("home");
        assertThat(again.source("HOME")).isNotNull();
        assertThat(again.source("Brave").id()).isEqualTo("brave");
        assertThat(WebSearchConfig.keyName(again.source("brave"))).isEqualTo("source:brave");

        // what cannot be read is no options
        com.google.gson.JsonObject broken = new com.google.gson.JsonObject();
        broken.addProperty("sources", "nonsense");
        assertThat(WebSearchConfig.from(broken).sources()).isEmpty();
    }
}
