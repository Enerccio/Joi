package com.github.enerccio.joi.extensions;

import com.vaadin.flow.component.Component;

/**
 * A tab an extension adds to the workspace, after the user's settings. Created once for every workspace that is opened while the
 * extension is loaded (a workspace that is open already shows it after a reload of the page), and gone when the extension is unloaded.
 */
public interface ExtensionPart {

    /** Names the tab for the state of the workspace (what tab was open): never changes, unique inside the extension. */
    String key();

    /** The caption of the tab. */
    String caption();

    /** Builds the content of the tab. */
    Component create() throws Exception;

    /** The tab was selected: loads the data again. */
    default void shown() throws Exception {
    }
}
