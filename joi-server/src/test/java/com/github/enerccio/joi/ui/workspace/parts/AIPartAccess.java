package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.vaadin.flow.data.provider.Query;

import java.util.List;

/**
 * Reads the rows of an {@link AIPart}'s grid for tests in other packages.
 */
public final class AIPartAccess {

    private AIPartAccess() {
    }

    public static List<AI> rows(WorkspacePart part) {
        return ((AIPart) part).getGrid().getDataProvider().fetch(new Query<>()).toList();
    }
}
