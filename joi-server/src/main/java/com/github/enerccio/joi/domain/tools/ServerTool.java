package com.github.enerccio.joi.domain.tools;

import java.util.Locale;

/**
 * A tool of the server that needs approval: it reaches outside (the web), so the administrator, the user and the
 * assistant each say whether it runs, waits for the user, or never runs (see {@code DESIGN.md} → Tool permissions).
 * Tools that only touch her own things (memory, body) are not here: they always run.
 */
public enum ServerTool {

    /** She searches the web and reads the pages it finds: a task of the stronger model. */
    WEB_SEARCH("web_search", ToolPermission.ASK),
    /** She reads a page the user gave her: a task of the stronger model. */
    FETCH_PAGE("fetch_page", ToolPermission.ASK);

    private final String function;
    private final ToolPermission fallback;

    ServerTool(String function, ToolPermission fallback) {
        this.function = function;
        this.fallback = fallback;
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
