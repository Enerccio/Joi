package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiWorkspaceService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileSystem;
import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.WorkspaceBrowser;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * The Files tab: her workspace, the files she keeps between her turns and her tasks write into. The platform's
 * {@link WorkspaceBrowser} on the same file system her functions use (the same limits, the same lock): browse, read, download,
 * upload, create a folder, delete, clear. What the user puts here, she can read.
 */
@Configurable
public class JoiFilesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiWorkspaceService workspaceService;

    private Joi joi;
    private WorkspaceBrowser browser;

    @Override
    public J_L caption() {
        return J_L.LABEL_FILES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.HELP_FILES_TAB));
        hint.addClassName(CssStyles.HINT_TEXT);
        // a task may change the files while they are looked at: the file system has a lock, so the browser is never only to look at
        browser = new WorkspaceBrowser(new WorkspaceBrowser.Source() {
            @Override
            public VirtualFileSystem open() throws Exception {
                return workspaceService.open(joi);
            }

            @Override
            public void clear() throws Exception {
                workspaceService.clear(joi);
            }

            @Override
            public WorkspaceLimits limits() {
                try {
                    return workspaceService.limits();
                } catch (Exception e) {
                    return WorkspaceLimits.DEFAULT;
                }
            }
        }, () -> false);
        browser.setPhone(UIUtils.isPhone());
        browser.create();
        VerticalLayout layout = new VerticalLayout(hint, browser);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.expand(browser);
        return layout;
    }

    @Override
    public void load(Joi joi) {
        this.joi = joi;
        browser.refresh();
    }

    @Override
    public void shown() {
        browser.refresh();
    }

    /** The browser, for tests. */
    public WorkspaceBrowser getBrowser() {
        return browser;
    }
}
