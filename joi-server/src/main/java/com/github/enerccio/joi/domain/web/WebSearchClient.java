package com.github.enerccio.joi.domain.web;

import com.github.enerccio.marginalia.domain.security.OutboundAddressGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One search in one source. The requests are those of the providers' documentation (see {@code DESIGN.md}, Web search
 * and reading pages); an address in the source replaces the provider's own (a mirror, a proxy, a fake server in the
 * tests). Nothing is kept: the caller gives the key and the proxy for the call.
 */
public final class WebSearchClient {

    static final String DDG = "https://html.duckduckgo.com/html/";
    static final String GOOGLE = "https://customsearch.googleapis.com/customsearch/v1";
    static final String BRAVE = "https://api.search.brave.com/res/v1/web/search";
    static final String TAVILY = "https://api.tavily.com/search";
    static final String PERPLEXITY = "https://api.perplexity.ai/search";
    private static final int SNIPPET = 500;

    private final WebSearchConfig config;
    private final boolean allowPrivate;

    /** A client whose sources may be anywhere, the server's own network included (the tests' fake servers). */
    public WebSearchClient(WebSearchConfig config) {
        this(config, true);
    }

    /**
     * @param allowPrivate whether the sources may be on the server's own network (localhost, 192.168...); otherwise
     *                     such an address, or a redirect to one, is refused
     */
    public WebSearchClient(WebSearchConfig config, boolean allowPrivate) {
        this.config = config;
        this.allowPrivate = allowPrivate;
    }

    /**
     * @param apiKey    the key of the source, null when it has none
     * @param proxyAuth {@code user:password} of the common proxy, null for none
     */
    public SearchResult search(SearchSource source, String apiKey, String proxyAuth, String query) throws WebException {
        if (StringUtils.isBlank(query)) {
            throw new WebException("There is nothing to search for");
        }
        if (source.type().needsKey() && StringUtils.isBlank(apiKey)) {
            throw new WebException("The source " + source.name() + " has no API key");
        }
        // the source's own proxy comes with its own credentials only through the common secret: none is guessed
        String proxy = StringUtils.defaultIfBlank(source.proxy(), config.proxy());
        if (!allowPrivate && StringUtils.isBlank(proxy) && StringUtils.isNotBlank(source.url())) {
            // early, with a readable answer; the client checks every connection again (redirects, a changed DNS answer)
            reachable(address(source, source.url()));
        }
        WebHttp http = new WebHttp(proxy, StringUtils.isBlank(source.proxy()) ? proxyAuth : null, config.timeoutSeconds(), config.userAgent(), !allowPrivate);
        String q = query.strip();
        int max = config.maxResults();
        SearchResult result = switch (source.type()) {
            case DDGS -> duckDuckGo(http, source, q);
            case SEARXNG -> searxng(http, source, q);
            case GOOGLE -> google(http, source, apiKey, q);
            case BRAVE -> brave(http, source, apiKey, q);
            case TAVILY -> tavily(http, source, apiKey, q);
            case PERPLEXITY -> perplexity(http, source, apiKey, q);
        };
        List<SearchHit> hits = result.hits().stream().filter(hit -> !hit.url().isBlank()).limit(max).toList();
        return new SearchResult(hits, result.answer());
    }

    // ----------------------------------------------------------------------------------------------------------------

    private SearchResult duckDuckGo(WebHttp http, SearchSource source, String query) throws WebException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", withSites(query, source));
        // kp: 1 is strict safe search, -2 is off
        form.put("kp", config.safeSearch() ? "1" : "-2");
        WebHttp.Response response = http.postForm(address(source, DDG), form, Map.of("Accept", "text/html"));
        Document page = Jsoup.parse(response.body(), response.uri().toString());
        List<SearchHit> hits = new ArrayList<>();
        for (Element result : page.select("div.result")) {
            if (result.hasClass("result--ad")) {
                continue;
            }
            Element link = result.selectFirst("a.result__a");
            if (link == null) {
                continue;
            }
            String url = duckUrl(link.attr("href"));
            if (url == null) {
                continue;
            }
            Element snippet = result.selectFirst(".result__snippet");
            hits.add(hit(link.text(), url, snippet == null ? "" : snippet.text(), source));
        }
        if (hits.isEmpty() && page.selectFirst("div.no-results") == null && page.selectFirst("div.result") == null) {
            throw new WebException("DuckDuckGo gave no results page (it may ask for a captcha, or its page changed)");
        }
        return new SearchResult(hits, null);
    }

    /** DuckDuckGo sends a result through {@code //duckduckgo.com/l/?uddg=<the address>}. */
    static String duckUrl(String href) {
        if (StringUtils.isBlank(href)) {
            return null;
        }
        String uddg = StringUtils.substringAfter(href, "uddg=");
        if (!uddg.isEmpty()) {
            return URLDecoder.decode(StringUtils.substringBefore(uddg, "&"), StandardCharsets.UTF_8);
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        return href.startsWith("http") ? href : null;
    }

    private SearchResult searxng(WebHttp http, SearchSource source, String query) throws WebException {
        if (StringUtils.isBlank(source.url())) {
            throw new WebException("The source " + source.name() + " needs the address of the SearXNG instance");
        }
        StringBuilder url = new StringBuilder(StringUtils.removeEnd(source.url().strip(), "/")).append("/search?format=json&q=")
                .append(WebHttp.encode(withSites(query, source)))
                .append("&safesearch=").append(config.safeSearch() ? 2 : 0);
        if (StringUtils.isNotBlank(config.language())) {
            url.append("&language=").append(WebHttp.encode(config.language()));
        }
        WebHttp.Response response;
        try {
            response = http.get(URI.create(url.toString()), Map.of("Accept", "application/json"), null);
        } catch (WebException e) {
            if (e.status() == 403) {
                throw new WebException("The SearXNG instance does not allow the json format (search.formats in its settings.yml)", 403, e);
            }
            throw e;
        }
        JsonObject json = json(response, source);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonElement item : array(json, "results")) {
            JsonObject result = item.getAsJsonObject();
            hits.add(hit(text(result, "title"), text(result, "url"), text(result, "content"), source));
        }
        return new SearchResult(hits, null);
    }

    private SearchResult google(WebHttp http, SearchSource source, String key, String query) throws WebException {
        if (StringUtils.isBlank(source.engineId())) {
            throw new WebException("The source " + source.name() + " needs the engine id (cx) of the Programmable Search Engine");
        }
        boolean oneSite = source.sites().size() == 1;
        StringBuilder url = new StringBuilder(address(source, GOOGLE).toString()).append("?key=").append(WebHttp.encode(key))
                .append("&cx=").append(WebHttp.encode(source.engineId()))
                .append("&q=").append(WebHttp.encode(oneSite ? query : withSites(query, source)))
                .append("&num=").append(Math.min(config.maxResults(), 10))
                .append("&safe=").append(config.safeSearch() ? "active" : "off");
        if (oneSite) {
            url.append("&siteSearch=").append(WebHttp.encode(source.sites().getFirst())).append("&siteSearchFilter=i");
        }
        if (StringUtils.isNotBlank(config.language())) {
            url.append("&hl=").append(WebHttp.encode(config.language())).append("&lr=lang_").append(WebHttp.encode(config.language()));
        }
        JsonObject json = json(http.get(URI.create(url.toString()), Map.of("Accept", "application/json"), null), source);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonElement item : array(json, "items")) {
            JsonObject result = item.getAsJsonObject();
            hits.add(hit(text(result, "title"), text(result, "link"), text(result, "snippet"), source));
        }
        return new SearchResult(hits, null);
    }

    private SearchResult brave(WebHttp http, SearchSource source, String key, String query) throws WebException {
        StringBuilder url = new StringBuilder(address(source, BRAVE).toString()).append("?q=").append(WebHttp.encode(withSites(query, source)))
                .append("&count=").append(Math.min(config.maxResults(), 20))
                .append("&safesearch=").append(config.safeSearch() ? "strict" : "moderate");
        if (StringUtils.isNotBlank(config.language())) {
            url.append("&search_lang=").append(WebHttp.encode(config.language()));
        }
        JsonObject json = json(http.get(URI.create(url.toString()), Map.of("Accept", "application/json", "X-Subscription-Token", key), null), source);
        List<SearchHit> hits = new ArrayList<>();
        JsonElement web = json.get("web");
        if (web != null && web.isJsonObject()) {
            for (JsonElement item : array(web.getAsJsonObject(), "results")) {
                JsonObject result = item.getAsJsonObject();
                hits.add(hit(text(result, "title"), text(result, "url"), text(result, "description"), source));
            }
        }
        return new SearchResult(hits, null);
    }

    private SearchResult tavily(WebHttp http, SearchSource source, String key, String query) throws WebException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("max_results", Math.min(config.maxResults(), 20));
        body.addProperty("include_answer", "basic");
        if (config.safeSearch()) {
            body.addProperty("safe_search", true);
        }
        if (StringUtils.isNotBlank(config.language())) {
            body.addProperty("language", config.language());
        }
        if (!source.sites().isEmpty()) {
            JsonArray domains = new JsonArray();
            source.sites().forEach(domains::add);
            body.add("include_domains", domains);
        }
        JsonObject json = json(http.postJson(address(source, TAVILY), body.toString(), Map.of("Authorization", "Bearer " + key)), source);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonElement item : array(json, "results")) {
            JsonObject result = item.getAsJsonObject();
            hits.add(hit(text(result, "title"), text(result, "url"), text(result, "content"), source));
        }
        return new SearchResult(hits, StringUtils.defaultIfBlank(text(json, "answer"), null));
    }

    private SearchResult perplexity(WebHttp http, SearchSource source, String key, String query) throws WebException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("max_results", Math.min(config.maxResults(), 20));
        if (!source.sites().isEmpty()) {
            JsonArray domains = new JsonArray();
            source.sites().forEach(domains::add);
            body.add("search_domain_filter", domains);
        }
        if (StringUtils.isNotBlank(config.language())) {
            JsonArray languages = new JsonArray();
            languages.add(config.language());
            body.add("search_language_filter", languages);
        }
        JsonObject json = json(http.postJson(address(source, PERPLEXITY), body.toString(), Map.of("Authorization", "Bearer " + key)), source);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonElement item : array(json, "results")) {
            JsonObject result = item.getAsJsonObject();
            hits.add(hit(text(result, "title"), text(result, "url"), text(result, "snippet"), source));
        }
        return new SearchResult(hits, null);
    }

    // ----------------------------------------------------------------------------------------------------------------

    /** The query with the source's sites in it, for the searches that have no parameter for them. */
    static String withSites(String query, SearchSource source) {
        if (source.sites().isEmpty()) {
            return query;
        }
        String sites = source.sites().stream().map(site -> "site:" + site).collect(Collectors.joining(" OR "));
        return query + (source.sites().size() > 1 ? " (" + sites + ")" : " " + sites);
    }

    /**
     * Refuses a source's address on the server's own network: a user's source must not send the server to its router or
     * another service of the machine unless the administrator allows local servers. Only for a readable answer early: the
     * client of {@link WebHttp} with {@code publicOnly} is what holds every connection to it.
     */
    private static void reachable(URI uri) throws WebException {
        if (uri.getHost() == null) {
            return;
        }
        try {
            OutboundAddressGuard.DEFAULT.resolvePublic(uri.getHost());
        } catch (OutboundAddressGuard.RefusedAddressException e) {
            throw new WebException(WebHttp.refused(e), e);
        }
    }

    private static URI address(SearchSource source, String own) throws WebException {
        String url = StringUtils.defaultIfBlank(source.url(), own).strip();
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new WebException("The address of " + source.name() + " is not an address: " + url);
        }
    }

    private static JsonObject json(WebHttp.Response response, SearchSource source) throws WebException {
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // not JSON
        }
        throw new WebException(source.name() + " did not answer with JSON (is the address right?)");
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static SearchHit hit(String title, String url, String snippet, SearchSource source) {
        return new SearchHit(StringUtils.defaultString(StringUtils.normalizeSpace(title)), StringUtils.defaultString(url),
                StringUtils.abbreviate(StringUtils.defaultString(StringUtils.normalizeSpace(snippet)), SNIPPET), source.name());
    }
}
