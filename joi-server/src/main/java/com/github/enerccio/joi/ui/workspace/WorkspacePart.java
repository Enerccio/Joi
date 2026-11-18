package com.github.enerccio.joi.ui.workspace;

import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.Restorable;
import com.google.gson.JsonObject;
import com.vaadin.flow.component.Component;

/**
 * A part of the {@link JoiWorkspace} (a tab on its left side). A part with something to remember between sessions
 * overrides the {@link Restorable} methods; the others have no state.
 */
public interface WorkspacePart extends Restorable, LLMInteract {

    /**
     * Builds the content of the tab, once per login.
     */
    Component create() throws Exception;

    /**
     * Loads the data again.
     */
    void refresh() throws Exception;

    /**
     * The tab was selected.
     */
    default void onTabSwitched() throws Exception {
        refresh();
    }

    /**
     * Another tab was selected.
     */
    default void onTabClosed() throws Exception {
    }

    @Override
    default JsonObject storeState() {
        return new JsonObject();
    }

    @Override
    default void restoreState(JsonObject self) {
    }
}
