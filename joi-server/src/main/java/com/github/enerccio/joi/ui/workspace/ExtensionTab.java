package com.github.enerccio.joi.ui.workspace;

import com.github.enerccio.joi.extensions.ExtensionPart;
import com.vaadin.flow.component.Component;

/** The {@link WorkspacePart} of a tab an extension added ({@link ExtensionPart}): it has no state to keep. */
final class ExtensionTab implements WorkspacePart {

    private final ExtensionPart part;

    ExtensionTab(ExtensionPart part) {
        this.part = part;
    }

    @Override
    public Component create() throws Exception {
        return part.create();
    }

    @Override
    public void refresh() {
    }

    @Override
    public void onTabSwitched() throws Exception {
        part.shown();
    }
}
