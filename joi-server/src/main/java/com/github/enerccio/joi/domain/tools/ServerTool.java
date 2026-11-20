package com.github.enerccio.joi.domain.tools;

import java.util.Locale;

/**
 * A tool of the server that needs approval: it reaches outside (the web), so the administrator, the user and the
 * assistant each say whether it runs, waits for the user, or never runs (see {@code DESIGN.md} → Tool permissions).
 * Tools that only touch her own things (memory, body) are not here: they always run.
 */
public enum ServerTool {

    /** She searches the web and reads the pages it finds: a task of the stronger model. */
    WEB_SEARCH("web_search", ToolPermission.ASK, "Search the web and read the pages found, with the stronger model"),
    /** She reads a page the user gave her: a task of the stronger model. */
    FETCH_PAGE("fetch_page", ToolPermission.ASK, "Read one page the user named and answer from it, with the stronger model");

    private final String function;
    private final ToolPermission fallback;
    private final String description;

    ServerTool(String function, ToolPermission fallback, String description) {
        this.function = function;
        this.fallback = fallback;
        this.description = description;
    }

    /** What the tool does, in a line: sent with a task, so a client can keep a standing approval as {name, description}. */
    public String description() {
        return description;
    }

    /** The name of the function she calls. */
    public String function() {
        return function;
    }

    /** What the tool is when no link of the chain says. */
    public ToolPermission fallback() {
        return fallback;
    }

    /** The tool of a function name, null for none. */
    public static ServerTool of(String function) {
        for (ServerTool tool : values()) {
            if (tool.function.equals(function) || tool.name().equals(function == null ? null : function.toUpperCase(Locale.ROOT))) {
                return tool;
            }
        }
        return null;
    }
}
