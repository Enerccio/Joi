package com.github.enerccio.joi.extensions;

import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import org.slf4j.Logger;

/**
 * What a {@link JoiExtension} may do to Joi: look up the server's services, and add things that go away with the extension (when it
 * is unloaded, stopped, replaced, or the server stops, what it registered here is removed: it needs no clean up of its own).
 */
public interface JoiExtensionContext {

    /** The symbolic name of the extension's bundle: who the registrations are of. */
    String owner();

    /** The logger of the extension: its own log file in the extensions folder (Administration → Extensions → Log). */
    Logger log();

    /**
     * A service or a repository of the server, for example {@code ChatService}, {@code JoiService}, {@code MemoryService}. Services
     * check their user as usual: call them in a request scope of a user (a turn, a page).
     *
     * @throws IllegalArgumentException when the server has no such bean
     */
    <T> T bean(Class<T> type);

    /** Offers functions to her turns, see {@link ToolProvider}. */
    void offerTools(ToolProvider provider);

    /** Adds a tab to the workspace, see {@link ExtensionPart}. */
    void addWorkspacePart(ExtensionPart part);

    /**
     * Listens to an event of every turn of every assistant, see {@link TurnEvent}: the same hook the server's own steps use to change
     * the prompt, the reply or the tools of a turn.
     */
    void onTurnEvent(TurnEvents event, TurnEvent listener);

    /**
     * Puts a function of the extension under the chain of permissions (see {@link ExtensionTool}): it is on the Tools pages and in her
     * Tasks tab, and a function of a {@link ToolProvider} of that name is offered only when the chain allows it. A name declared twice
     * (here or by another extension, or a tool of the server) is refused.
     *
     * @throws IllegalArgumentException when the name is taken
     */
    void declareTool(ExtensionTool tool);

    /**
     * Offers a function to her turns that runs as a task, see {@link ExtensionTask}; its tool is declared with it.
     *
     * @throws IllegalArgumentException when the name is taken
     */
    void offerTask(ExtensionTask task);

    /**
     * Asks for settings (an address, a key...), shown on the Tools pages of the administrator and the user, see
     * {@link ExtensionSettings}. Once per extension: a second call replaces the first.
     */
    void declareSettings(ExtensionSettings settings);

    /**
     * The extension's settings in force for the assistant (null: the user's, else the administrator's). Call it in the request scope
     * of a user (a turn, a task, a page): the user's place is the current user's.
     */
    ExtensionConfig settings(Joi joi) throws Exception;
}
