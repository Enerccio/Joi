package com.github.enerccio.joi.ui.tools;

import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/**
 * The tools that need approval in one place of the chain: their permissions, the options of the web tools and the settings of the
 * extensions. Administration
 * → Tools (the administrator's) and User Settings → Tools (the user's own).
 */
public class ToolsPanel {

    private final ToolPermissionsPanel permissions;
    private final WebSearchPanel webSearch;
    private final ExtensionSettingsPanel extensionSettings;

    public ToolsPanel(ToolScope scope) {
        permissions = new ToolPermissionsPanel(scope);
        webSearch = new WebSearchPanel(scope);
        extensionSettings = new ExtensionSettingsPanel(scope);
    }

    public Component create() throws Exception {
        VerticalLayout layout = new VerticalLayout(permissions.create(), webSearch.create(), extensionSettings.create());
        layout.setWidthFull();
        layout.setPadding(true);
        layout.addClassName(JoiCssStyles.TOOLS_PANEL);
        permissions.load(null);
        return layout;
    }

    /** Shows what is stored now. */
    public void refresh() throws Exception {
        permissions.load(null);
        webSearch.refresh();
        extensionSettings.refresh();
    }

    public ToolPermissionsPanel getPermissions() {
        return permissions;
    }

    public ExtensionSettingsPanel getExtensionSettings() {
        return extensionSettings;
    }

    public WebSearchPanel getWebSearch() {
        return webSearch;
    }
}
