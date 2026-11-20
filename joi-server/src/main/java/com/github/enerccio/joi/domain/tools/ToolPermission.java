package com.github.enerccio.joi.domain.tools;

import java.util.Locale;

/**
 * What the server does when she calls a tool that needs approval ({@link ServerTool}). Stored by name, so the names
 * never change. The order is the order of strictness: a link of the chain ({@code ToolPermissionService}) can only be
 * stricter than the administrator's.
 */
public enum ToolPermission {

    /** The tool runs at once. */
    ALLOW,
    /** The tool waits for the user's answer (the Tasks tab of her dialog, or the API). */
    ASK,
    /** The tool never runs; she is told it is not allowed. */
    DENY;

    /** The stricter of the two; a null is no opinion. */
    public static ToolPermission stricter(ToolPermission a, ToolPermission b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The permission of a stored name, null for none or one nobody knows. */
    public static ToolPermission parse(String name) {
        if (name == null) {
            return null;
        }
        for (ToolPermission permission : values()) {
            if (permission.name().equals(name.strip().toUpperCase(Locale.ROOT))) {
                return permission;
            }
        }
        return null;
    }
}
