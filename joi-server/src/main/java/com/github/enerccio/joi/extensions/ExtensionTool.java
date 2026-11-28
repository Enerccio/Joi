package com.github.enerccio.joi.extensions;

import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A function of an extension that the chain of permissions decides on, as it does on the server's own {@code web_search}: the
 * administrator, the user and the assistant each say whether it runs, waits for the user or never runs (the Tools pages and her Tasks
 * tab). Declare it with {@link JoiExtensionContext#declareTool}; a task ({@link ExtensionTask}) declares its own.
 * <p>
 * A function she calls in her turn ({@link ToolProvider}) whose name is declared is offered when the chain allows it, and left out of
 * the turn when it says deny or ask: nobody can be asked in the middle of a turn, so a function that should ask is a task.
 *
 * @param function    the name of her function, also the key of its settings: never change it ({@code [a-z0-9_]}, at most 64)
 * @param label       its name on the Tools pages
 * @param description what it does, in a line (shown with a task that waits, and kept with a standing approval)
 * @param fallback    what it is when nobody set it
 */
public record ExtensionTool(String function, String label, String description, ToolPermission fallback) implements ApprovableTool {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ExtensionTool {
        Objects.requireNonNull(fallback, "fallback");
        if (function == null || !NAME.matcher(function).matches()) {
            throw new IllegalArgumentException("The name of a tool is [a-z][a-z0-9_]*, at most 64: " + function);
        }
        label = label == null || label.isBlank() ? function : label.strip();
        description = description == null ? "" : description.strip();
    }
}
