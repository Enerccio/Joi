package com.github.enerccio.joi.domain.web;

/** The kinds of search a source can be. Stored by name in the tool's options, so the names never change. */
public enum SearchSourceType {

    /** DuckDuckGo's HTML page: no key, no official API. */
    DDGS(false),
    /** A SearXNG instance's JSON API; its URL is needed. */
    SEARXNG(false),
    /** Google's Programmable Search (Custom Search JSON API); a key and an engine id. Closed to new customers. */
    GOOGLE(true),
    /** Brave's Search API; a key. */
    BRAVE(true),
    /** Tavily, an AI search that also gives an answer; a key. */
    TAVILY(true),
    /** Perplexity's Search API; a key. */
    PERPLEXITY(true);

    private final boolean key;

    SearchSourceType(boolean key) {
        this.key = key;
    }

    /** Whether it needs an API key. */
    public boolean needsKey() {
        return key;
    }
}
