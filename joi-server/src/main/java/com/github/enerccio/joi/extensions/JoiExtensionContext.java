package com.github.enerccio.joi.extensions;

import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
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
}
