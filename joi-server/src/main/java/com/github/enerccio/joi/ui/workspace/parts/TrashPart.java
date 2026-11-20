package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.widgets.TrashGrid;
import com.github.enerccio.marginalia.ui.workspace.BaseWorkspace;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.List;

/**
 * What the user deleted (the assistants, models, animations, memories, messages...): it stays in the trash and can be
 * restored until an administrator runs the cleanup (Administration → Cleanup). It is the platform's {@link TrashGrid},
 * as in Marginalia: a user sees their own deleted objects, an administrator the ones of all users. An object cannot
 * be restored while one it depends on is deleted too, unless that one is restored with it.
 */
@Configurable
public class TrashPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private AdminGuard adminGuard;

    private final BaseWorkspace workspace;
    private TrashGrid trashGrid;

    public TrashPart(BaseWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public Component create() throws Exception {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(true);
        layout.setSpacing(true);

        Span title = new Span(loc.getValue(L.LABEL_TRASH));
        title.addClassName(CssStyles.SECTION_TITLE);

        trashGrid = new TrashGrid(adminGuard.isAdmin()).create();
        // what was restored is back in its own tab
        trashGrid.addRestoreListener(() -> {
            try {
                workspace.refresh();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        });

        layout.add(title, trashGrid);
        layout.setFlexGrow(1, trashGrid);
        return layout;
    }

    @Override
    public void refresh() {
        if (trashGrid != null) {
            trashGrid.refresh();
        }
    }

    @Override
    public List<LLMInteract> getLLMChildren(boolean deep) {
        return trashGrid == null ? List.of() : List.of(trashGrid);
    }
}
