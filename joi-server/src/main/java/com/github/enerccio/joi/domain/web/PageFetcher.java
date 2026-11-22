package com.github.enerccio.joi.domain.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one page for the model: as text, or Markdown from a crawl4ai server, cut to {@link PageConfig#maxChars()}.
 * Fetching is anonymous: no cookie of the user's browser, none kept between calls. What comes back is data from the
 * outside, never instructions (the task's prompt says so).
 */
public final class PageFetcher {

    private static final String BLOCKS = "h1, h2, h3, h4, h5, h6, p, li, pre, blockquote, td, th";

    /** What a crawl4ai-based reader says when it failed, in the text it answers with. */
    private static final String[] PROXY_ERRORS = {"Crawl4AI Proxy Error:", "Error:"};

    private final PageConfig config;
    private final String token;
    private final String userAgent;
    private final String proxyAuth;

    /**
     * @param token     the bearer token of the crawl4ai server, null for none
     * @param proxyAuth {@code user:password} of the proxy of direct reads, null for none
     */
    public PageFetcher(PageConfig config, String token, String proxyAuth, String userAgent) {
        this.config = config;
        this.token = token;
        this.proxyAuth = proxyAuth;
        this.userAgent = userAgent;
    }

    /** The page as text (Markdown from crawl4ai), at most {@code maxChars} of it. */
    public String fetch(String page) throws WebException {
        URI uri = target(page);
        String text = switch (config.type()) {
            case DIRECT -> direct(uri);
            case CRAWL4AI -> crawl4ai(uri);
            case CRAWL4AI_PROXY -> proxy(uri);
        };
        text = tidy(text);
        if (text.isEmpty()) {
            throw new WebException("The page has no text to read");
        }
        return text.length() > config.maxChars() ? text.substring(0, config.maxChars()) + "\n\n[The page goes on; it was cut here.]" : text;
    }

    private static URI target(String page) throws WebException {
        URI uri;
        try {
            uri = URI.create(StringUtils.defaultString(page).strip());
        } catch (IllegalArgumentException e) {
            throw new WebException("That is not an address: " + page);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || StringUtils.isBlank(uri.getHost())) {
            throw new WebException("Only http and https addresses can be read: " + page);
        }
        return uri;
    }

    // ----------------------------------------------------------------------------------------------------------------

    private String direct(URI uri) throws WebException {
        // without a proxy the client also holds every connection to a public address, so a name whose DNS answer changes
        // after checkReachable does not reach the server's network; a proxy (it may be the administrator's, on that
        // network) looks the page up itself and is not refused
        boolean pinned = !config.allowPrivate() && StringUtils.isBlank(config.proxy());
        WebHttp http = new WebHttp(config.proxy(), proxyAuth, config.timeoutSeconds(), userAgent, pinned);
        WebHttp.Response response;
        try {
            response = http.get(uri, Map.of("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5"), this::checkReachable);
        } catch (IllegalStateException e) {
            throw new WebException(e.getMessage(), e);
        }
        String type = response.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/plain") || type.contains("markdown")) {
            return response.body();
        }
        if (!type.isEmpty() && !type.contains("html") && !type.contains("xml")) {
            throw new WebException("The page is " + StringUtils.substringBefore(type, ";") + ", not text");
        }
        return text(Jsoup.parse(response.body(), response.uri().toString()));
    }

    /**
     * Refuses an address of the server's own network (and every address a redirect leads to): without it a page she is
     * led to could send the server to its router, its metadata service or another service of the machine.
     */
    private void checkReachable(URI uri) throws IllegalStateException {
        if (config.allowPrivate()) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocal(address)) {
                    throw new Private(uri.getHost());
                }
            }
        } catch (UnknownHostException e) {
            throw new Unknown(uri.getHost());
        }
    }

    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    /** Carries the refusal out of the redirect check, which cannot throw a checked exception. */
    private static class Private extends IllegalStateException {
        Private(String host) {
            super("The address " + host + " is on the server's own network, which pages are not read from");
        }
    }

    private static class Unknown extends IllegalStateException {
        Unknown(String host) {
            super("There is no such host: " + host);
        }
    }

    /** The readable part of a page: headings, paragraphs, lists and code, with its title; no navigation, scripts or forms. */
    static String text(Document page) {
        page.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, template").remove();
        Element root = page.selectFirst("main, article, [role=main]");
        if (root == null) {
            root = page.body();
        }
        StringBuilder out = new StringBuilder();
        if (StringUtils.isNotBlank(page.title())) {
            out.append("# ").append(StringUtils.normalizeSpace(page.title())).append("\n\n");
        }
        if (root != null) {
            for (Element element : root.select(BLOCKS)) {
                // a block that holds others (a list item with a list in it) is left to the blocks inside it
                if (element.select(BLOCKS).size() > 1) {
                    continue;
                }
                String text = element.tagName().equals("pre") ? element.wholeText().strip() : StringUtils.normalizeSpace(element.text());
                if (text.isEmpty()) {
                    continue;
                }
                switch (element.tagName()) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" ->
                            out.append("#".repeat(element.tagName().charAt(1) - '0')).append(' ').append(text).append("\n\n");
                    case "li", "td", "th" -> out.append("- ").append(text).append('\n');
                    case "pre" -> out.append("```\n").append(text).append("\n```\n\n");
                    case "blockquote" -> out.append("> ").append(text).append("\n\n");
                    default -> out.append(text).append("\n\n");
                }
            }
        }
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------------------------

    private String crawl4ai(URI page) throws WebException {
        JsonObject body = new JsonObject();
        JsonArray urls = new JsonArray();
        urls.add(page.toString());
        body.add("urls", urls);
        JsonObject json = object(post(body));
        if (json.has("success") && json.get("success").isJsonPrimitive() && !json.get("success").getAsBoolean()) {
            throw new WebException("crawl4ai could not read the page" + reason(json));
        }
        JsonElement results = json.get("results");
        if (results == null || !results.isJsonArray() || results.getAsJsonArray().isEmpty()) {
            throw new WebException("crawl4ai gave no result for the page");
        }
        JsonObject first = results.getAsJsonArray().get(0).getAsJsonObject();
        if (first.has("success") && first.get("success").isJsonPrimitive() && !first.get("success").getAsBoolean()) {
            throw new WebException("crawl4ai could not read the page" + reason(first));
        }
        JsonElement markdown = first.get("markdown");
        String text = null;
        if (markdown != null && markdown.isJsonPrimitive()) {
            text = markdown.getAsString();
        } else if (markdown != null && markdown.isJsonObject()) {
            JsonObject object = markdown.getAsJsonObject();
            text = StringUtils.firstNonBlank(string(object, "fit_markdown"), string(object, "raw_markdown"));
        }
        return StringUtils.firstNonBlank(text, string(first, "raw_markdown"), "");
    }

    private String proxy(URI page) throws WebException {
        JsonObject body = new JsonObject();
        body.addProperty("url", page.toString());
        String content = string(object(post(body)), "content");
        for (String error : PROXY_ERRORS) {
            if (content != null && content.startsWith(error)) {
                throw new WebException("The page reader said: " + StringUtils.abbreviate(content, 200));
            }
        }
        return StringUtils.defaultString(content);
    }

    /** The reader's own address with {@code /crawl}, unless it has it already. */
    private WebHttp.Response post(JsonObject body) throws WebException {
        if (StringUtils.isBlank(config.url())) {
            throw new WebException("The page reader has no address");
        }
        String url = StringUtils.removeEnd(config.url().strip(), "/");
        if (!url.endsWith("/crawl")) {
            url += "/crawl";
        }
        WebHttp http = new WebHttp(null, null, config.timeoutSeconds(), userAgent);
        Map<String, String> headers = StringUtils.isBlank(token) ? Map.of() : Map.of("Authorization", "Bearer " + token);
        try {
            return http.postJson(URI.create(url), body.toString(), headers);
        } catch (IllegalArgumentException e) {
            throw new WebException("The address of the page reader is not an address: " + url);
        }
    }

    private static JsonObject object(WebHttp.Response response) throws WebException {
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // not JSON
        }
        throw new WebException("The page reader did not answer with JSON (is the address right?)");
    }

    private static String reason(JsonObject object) {
        String message = StringUtils.firstNonBlank(string(object, "error_message"), string(object, "error"));
        return message == null ? "" : ": " + StringUtils.abbreviate(StringUtils.normalizeSpace(message), 200);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** No runs of blank lines, no trailing spaces. */
    static String tidy(String text) {
        return StringUtils.defaultString(text).replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
