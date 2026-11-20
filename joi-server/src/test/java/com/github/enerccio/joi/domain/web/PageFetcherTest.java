package com.github.enerccio.joi.domain.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three ways to read a page, against a fake server: what is asked, what is made of the answer, and what is refused. */
class PageFetcherTest {

    private static final String PAGE = """
            <html><head><title>The  Title</title><script>var secret = 1;</script><style>p{}</style></head>
            <body><nav><a href="/">Home</a><a href="/about">About</a></nav>
            <main><h1>Heading</h1><p>First   paragraph
            of text.</p><ul><li>One</li><li>Two<ul><li>Nested</li></ul></li></ul><pre>code  line
            two</pre><blockquote>A quote.</blockquote></main>
            <footer>Copyright</footer></body></html>""";

    private static PageConfig direct(int maxChars, boolean allowPrivate) {
        return new PageConfig(PageSourceType.DIRECT, null, false, null, 5, maxChars, allowPrivate);
    }

    private static PageConfig reader(PageSourceType type, String url) {
        return new PageConfig(type, url, true, null, 5, 0, false);
    }

    @Test
    void aPageBecomesItsTextWithoutNavigationScriptsOrFooter() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html(PAGE))) {
            String text = new PageFetcher(direct(0, true), null, null, null).fetch(web.url() + "/page");
            assertThat(text).startsWith("# The Title\n\n# Heading\n\nFirst paragraph of text.")
                    .contains("- One\n").contains("- Nested\n").contains("```\ncode  line\ntwo\n```").contains("> A quote.");
            assertThat(text).doesNotContain("secret").doesNotContain("Home").doesNotContain("Copyright");
            assertThat(web.last().header("Cookie")).isNull();
        }
    }

    @Test
    void aLongPageIsCutAndSaysSo() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html("<html><body><p>" + "word ".repeat(2000) + "</p></body></html>"))) {
            String text = new PageFetcher(direct(100, true), null, null, null).fetch(web.url());
            assertThat(text).hasSizeLessThan(200).endsWith("[The page goes on; it was cut here.]");
        }
    }

    @Test
    void plainTextIsTakenAsItIsAndOtherTypesAreRefused() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(200, "text/plain", "just  text\n\n\n\nmore"))) {
            assertThat(new PageFetcher(direct(0, true), null, null, null).fetch(web.url())).isEqualTo("just  text\n\nmore");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(200, "application/pdf", "%PDF"))) {
            assertThatThrownBy(() -> new PageFetcher(direct(0, true), null, null, null).fetch(web.url()))
                    .isInstanceOf(WebException.class).hasMessageContaining("application/pdf");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html("<html><body><script>x</script></body></html>"))) {
            assertThatThrownBy(() -> new PageFetcher(direct(0, true), null, null, null).fetch(web.url()))
                    .isInstanceOf(WebException.class).hasMessageContaining("no text");
        }
    }

    @Test
    void aDirectReadNeverReachesTheServersOwnNetworkUnlessItIsAllowed() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.html(PAGE))) {
            for (String address : new String[]{web.url(), "http://localhost:" + web.port() + "/", "http://[::1]:" + web.port() + "/",
                    "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data/", "http://10.0.0.1/", "http://0.0.0.0/"}) {
                assertThatThrownBy(() -> new PageFetcher(direct(0, false), null, null, null).fetch(address))
                        .as(address).isInstanceOf(WebException.class).hasMessageContaining("own network");
            }
            assertThat(web.seen).isEmpty();
            assertThat(new PageFetcher(direct(0, true), null, null, null).fetch(web.url())).contains("Heading");
        }
    }

    @Test
    void onlyHttpAddressesAreRead() {
        for (String address : new String[]{"file:///etc/passwd", "ftp://example.org/x", "javascript:alert(1)", "not an address", "http:///x", ""}) {
            assertThatThrownBy(() -> new PageFetcher(direct(0, true), null, null, null).fetch(address)).as(address).isInstanceOf(WebException.class);
        }
    }

    @Test
    void crawl4aiIsAskedWithItsTokenAndTheFitMarkdownIsPreferred() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("""
                {"success":true,"results":[{"url":"https://x.example","success":true,
                 "markdown":{"raw_markdown":"RAW\\n\\n\\n\\ntext","fit_markdown":"# Fit\\n\\nbody   \\n\\n\\n\\nmore"}}]}"""))) {
            String text = new PageFetcher(reader(PageSourceType.CRAWL4AI, web.url()), "tok-1", null, null).fetch("https://x.example/a?b=1");
            FakeWeb.Seen seen = web.last();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.uri()).isEqualTo("/crawl");
            assertThat(seen.header("Authorization")).isEqualTo("Bearer tok-1");
            assertThat(seen.body()).contains("\"urls\":[\"https://x.example/a?b=1\"]");
            assertThat(text).isEqualTo("# Fit\n\nbody\n\nmore");
        }
        // raw when there is no fit, a plain string, or the raw_markdown of the result
        for (String markdown : new String[]{"\"markdown\":{\"raw_markdown\":\"RAW\"}", "\"markdown\":\"RAW\"", "\"raw_markdown\":\"RAW\""}) {
            try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"results\":[{" + markdown + "}]}"))) {
                assertThat(new PageFetcher(reader(PageSourceType.CRAWL4AI, web.url() + "/crawl"), null, null, null).fetch("https://x.example")).isEqualTo("RAW");
                assertThat(web.last().uri()).isEqualTo("/crawl");
                assertThat(web.last().header("Authorization")).isNull();
            }
        }
    }

    @Test
    void crawl4aiFailuresSayWhy() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"success\":true,\"results\":[{\"success\":false,\"error_message\":\"net::ERR_NAME_NOT_RESOLVED\"}]}"))) {
            assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI, web.url()), null, null, null).fetch("https://x.example"))
                    .isInstanceOf(WebException.class).hasMessageContaining("could not read the page").hasMessageContaining("ERR_NAME_NOT_RESOLVED");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"success\":true,\"results\":[]}"))) {
            assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI, web.url()), null, null, null).fetch("https://x.example"))
                    .hasMessageContaining("no result");
        }
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.status(401, "application/json", "{\"detail\":\"Not authenticated\"}"))) {
            assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI, web.url()), null, null, null).fetch("https://x.example"))
                    .isInstanceOfSatisfying(WebException.class, e -> assertThat(e.status()).isEqualTo(401));
        }
        assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI, null), null, null, null).fetch("https://x.example"))
                .hasMessageContaining("no address");
    }

    @Test
    void theUsersOwnProxyInFrontOfCrawl4aiTakesTheUrlAndAnswersContent() throws Exception {
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"content\":\"# Page\\n\\n\\n\\ntext\"}"))) {
            String text = new PageFetcher(reader(PageSourceType.CRAWL4AI_PROXY, web.url()), null, null, null).fetch("https://x.example/a");
            assertThat(web.last().uri()).isEqualTo("/crawl");
            assertThat(web.last().body()).isEqualTo("{\"url\":\"https://x.example/a\"}");
            assertThat(text).isEqualTo("# Page\n\ntext");
        }
        // it answers 200 with the error as the text: that is a failure, not a page
        for (String error : new String[]{"Crawl4AI Proxy Error: Expecting value", "Error: No target URL provided."}) {
            try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"content\":\"" + error + "\"}"))) {
                assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI_PROXY, web.url()), null, null, null).fetch("https://x.example"))
                        .isInstanceOf(WebException.class).hasMessageContaining("The page reader said").hasMessageContaining(error);
            }
        }
        // an empty page is not a page
        try (FakeWeb web = new FakeWeb(FakeWeb.Reply.json("{\"content\":\"  \"}"))) {
            assertThatThrownBy(() -> new PageFetcher(reader(PageSourceType.CRAWL4AI_PROXY, web.url()), null, null, null).fetch("https://x.example"))
                    .hasMessageContaining("no text");
        }
    }

    @Test
    void theConfigIsReadWithItsDefaults() {
        PageConfig empty = PageConfig.from(null);
        assertThat(empty.type()).isEqualTo(PageSourceType.DIRECT);
        assertThat(empty.timeoutSeconds()).isEqualTo(PageConfig.TIMEOUT);
        assertThat(empty.maxChars()).isEqualTo(PageConfig.MAX_CHARS);
        assertThat(empty.allowPrivate()).isFalse();
        PageConfig kept = new PageConfig(PageSourceType.CRAWL4AI_PROXY, "http://crawl.lan:8000", true, null, 12, 5000, false);
        assertThat(PageConfig.from(kept.toJson())).isEqualTo(kept);
    }
}
