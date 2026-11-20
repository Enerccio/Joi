package com.github.enerccio.joi.domain.web;

/** How a page is read. Stored by name in the tool's options, so the names never change. */
public enum PageSourceType {

    /** The server gets the page itself and strips it to text. Never to addresses of the server's own network. */
    DIRECT,
    /** A crawl4ai server's own API: {@code POST /crawl} with {@code urls}. */
    CRAWL4AI,
    /**
     * A small proxy in front of crawl4ai that takes {@code {"url": ...}} and answers {@code {"content": markdown}},
     * reporting an error as text in {@code content}.
     */
    CRAWL4AI_PROXY
}
