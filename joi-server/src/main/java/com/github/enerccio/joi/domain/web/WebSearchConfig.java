package com.github.enerccio.joi.domain.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The options of {@code web_search}: what is in the {@code config} of its tool settings ({@code ToolMetadata}).
 * A value that is missing or silly gets its default when read ({@link #from}).
 *
 * @param sources        the places to search, in the order they are tried
 * @param proxy          an HTTP(S) proxy for all of them ({@code http://host:port}); its user and password are the
 *                       secret {@value #PROXY_SECRET}, {@code user:password}
 * @param timeoutSeconds how long one request may take
 * @param maxResults     how many results one search gives
 * @param language       the language of the results (an ISO 639-1 code), blank for the provider's choice
 * @param safeSearch     ask the provider to leave out what is not safe for work
 * @param userAgent      what the requests say they are, blank for the default
 * @param localOnly      use only the sources marked local (and pages from a local reader)
 * @param maxSearches    how many searches one task may make
 * @param maxPages       how many pages one task may read
 */
public record WebSearchConfig(List<SearchSource> sources, String proxy, int timeoutSeconds, int maxResults, String language,
                              boolean safeSearch, String userAgent, boolean localOnly, int maxSearches, int maxPages) {

    private static final Gson GSON = new Gson();

    public static final String PROXY_SECRET = "proxy";
    public static final int TIMEOUT = 20;
    public static final int RESULTS = 8;
    public static final int SEARCHES = 6;
    public static final int PAGES = 8;

    public WebSearchConfig {
        sources = sources == null ? List.of() : List.copyOf(sources);
        timeoutSeconds = timeoutSeconds <= 0 ? TIMEOUT : Math.min(timeoutSeconds, 300);
        maxResults = maxResults <= 0 ? RESULTS : Math.min(maxResults, 50);
        maxSearches = maxSearches <= 0 ? SEARCHES : Math.min(maxSearches, 50);
        maxPages = maxPages <= 0 ? PAGES : Math.min(maxPages, 50);
    }

    /** The options in the JSON of the settings; the defaults when it is empty or cannot be read. */
    public static WebSearchConfig from(JsonObject json) {
        try {
            WebSearchConfig read = json == null ? null : GSON.fromJson(json, WebSearchConfig.class);
            if (read != null) {
                return read;
            }
        } catch (RuntimeException e) {
            // a broken text is no options
        }
        return new WebSearchConfig(null, null, 0, 0, null, false, null, false, 0, 0);
    }

    /** The JSON to keep. */
    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }

    /** The name of the secret that holds the API key of the source. */
    public static String keyName(SearchSource source) {
        return "source:" + source.id();
    }

    /** The source of the id or the name, null for none. */
    public SearchSource source(String idOrName) {
        if (idOrName == null) {
            return null;
        }
        return sources.stream().filter(s -> idOrName.equalsIgnoreCase(s.id())).findFirst()
                .orElseGet(() -> sources.stream().filter(s -> idOrName.equalsIgnoreCase(s.name())).findFirst().orElse(null));
    }

    /** The sources that may be used now. */
    public List<SearchSource> usable() {
        return sources.stream().filter(s -> s.enabled() && (!localOnly || s.local())).toList();
    }
}
