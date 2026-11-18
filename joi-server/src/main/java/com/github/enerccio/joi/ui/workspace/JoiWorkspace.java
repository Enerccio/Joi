package com.github.enerccio.joi.ui.workspace;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.workspace.parts.AIPart;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.loc.LocalizationKey;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.RestorableState;
import com.github.enerccio.marginalia.ui.dialogs.UserDialog;
import com.github.enerccio.marginalia.ui.widgets.HTabSheet;
import com.github.enerccio.marginalia.ui.widgets.LanguageSelector;
import com.github.enerccio.marginalia.ui.widgets.ThemeSwitch;
import com.github.enerccio.marginalia.ui.workspace.BaseWorkspace;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.google.gson.JsonObject;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joi's page after the login: the parts as tabs on the left, the account actions (language, theme, password, logout)
 * under them. Its {@link com.github.enerccio.marginalia.ui.Restorable} state is the selected tab and the state of the
 * parts, kept in the user setting by {@link WorkspaceAutosave}.
 */
@Configurable
public class JoiWorkspace implements BaseWorkspace {

    @Autowired
    private Localization loc;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    private HTabSheet tabs;

    // the parts under the keys of the stored state, in the order of the tabs; a key never changes
    private final Map<String, WorkspacePart> partsByKey = new LinkedHashMap<>();
    {
        partsByKey.put("models", new AIPart());
    }

    private final Map<Tab, WorkspacePart> tabToPart = new HashMap<>();
    private final Map<WorkspacePart, Component> partComponents = new LinkedHashMap<>();
    private final WorkspaceAutosave autosave = new WorkspaceAutosave(this);

    private WorkspacePart activePart;
    private Button logoutButton;
    private Button changePasswordButton;
    private LanguageSelector languageSelector;
    private boolean navigationLocked;
    private Runnable onLogout;

    @Override
    public Component create() throws Exception {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();

        tabs = new HTabSheet();
        tabs.setSizeFull();
        tabs.setTabsWidth("260px");
        tabs.setTabsMaxWidth("300px");
        layout.add(tabs);

        addTab("models", PartIcon.MODELS, J_L.LABEL_MODELS);

        tabs.setFooterComponent(createFooter());
        tabs.addSelectedChangeListener(e -> {
            if (activePart != null) {
                try {
                    activePart.onTabClosed();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            }
            activePart = tabToPart.get(e.getSelectedTab());
            if (activePart != null) {
                try {
                    activePart.onTabSwitched();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            }
            autosave();
        });
        activePart = tabToPart.get(tabs.getSelectedTab());
        return layout;
    }

    private void addTab(String key, PartIcon icon, LocalizationKey caption) throws Exception {
        WorkspacePart part = partsByKey.get(key);
        Component content = part.create();
        partComponents.put(part, content);

        HorizontalLayout header = new HorizontalLayout(icon.create(), new Span(loc.getValue(caption)));
        header.setAlignItems(Alignment.CENTER);
        header.setSpacing(true);

        Tab tab = tabs.add(header, content);
        tabToPart.put(tab, part);
    }

    private Component createFooter() {
        VerticalLayout footer = new VerticalLayout();
        footer.setPadding(false);
        footer.setSpacing(true);
        footer.setWidthFull();

        languageSelector = new LanguageSelector();
        languageSelector.setWidthFull();
        HorizontalLayout languageRow = new HorizontalLayout(languageSelector, new ThemeSwitch());
        languageRow.setWidthFull();
        languageRow.setPadding(false);
        languageRow.setAlignItems(Alignment.END);
        languageRow.setFlexGrow(1, languageSelector);
        footer.add(languageRow);

        changePasswordButton = new Button(loc.getValue(L.LABEL_CHANGE_PASSWORD), Solid.KEY.create());
        changePasswordButton.setWidthFull();
        changePasswordButton.addClickListener(e -> {
            try {
                UserDialog dialog = new UserDialog(userService.find(currentUser.getId()), true);
                dialog.setSelfEdit(true);
                dialog.create();
                dialog.open();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        footer.add(changePasswordButton);

        if (onLogout != null) {
            logoutButton = new Button(loc.getValue(J_L.LABEL_LOGOUT), Solid.SIGN_OUT_ALT.create());
            logoutButton.setWidthFull();
            logoutButton.addClickListener(e -> onLogout.run());
            footer.add(logoutButton);
        }
        return footer;
    }

    @Override
    public void refresh() throws Exception {
        for (WorkspacePart part : partsByKey.values()) {
            part.refresh();
        }
    }

    @Override
    public void setNavigationLocked(boolean locked) {
        navigationLocked = locked;
        Tab selected = tabs.getSelectedTab();
        for (Tab tab : tabToPart.keySet()) {
            tab.setEnabled(!locked || tab.equals(selected));
        }
        for (HasEnabled control : new HasEnabled[]{languageSelector, changePasswordButton, logoutButton}) {
            if (control != null) {
                control.setEnabled(!locked);
            }
        }
    }

    @Override
    public boolean isNavigationLocked() {
        return navigationLocked;
    }

    @Override
    public void autosave() {
        autosave.save();
    }

    @Override
    public void restoreAutosaved() {
        autosave.restore();
    }

    @Override
    public Runnable getOnLogout() {
        return onLogout;
    }

    @Override
    public void setOnLogout(Runnable onLogout) {
        this.onLogout = onLogout;
    }

    /**
     * The part of the selected tab.
     */
    public WorkspacePart getActivePart() {
        return activePart;
    }

    /**
     * The part stored under the key ({@code "models"}), null for an unknown key.
     */
    public WorkspacePart getPart(String key) {
        return partsByKey.get(key);
    }

    @Override
    public List<LLMInteract> getLLMChildren(boolean deep) {
        return LLMInteract.parts(new LinkedHashMap<>(partComponents), activePart, deep);
    }

    @Override
    public JsonObject storeState() {
        JsonObject state = new JsonObject();
        JsonObject parts = new JsonObject();
        WorkspacePart selected = tabs == null ? null : tabToPart.get(tabs.getSelectedTab());
        partsByKey.forEach((key, part) -> {
            RestorableState.put(parts, key, part.storeState());
            if (part == selected) {
                state.addProperty("tab", key);
            }
        });
        RestorableState.put(state, "parts", parts);
        return state;
    }

    @Override
    public void restoreState(JsonObject self) {
        JsonObject parts = RestorableState.object(self, "parts");
        partsByKey.forEach((key, part) -> {
            JsonObject partState = RestorableState.object(parts, key);
            if (partState != null) {
                part.restoreState(partState);
            }
        });
        WorkspacePart part = partsByKey.get(RestorableState.string(self, "tab"));
        if (part != null) {
            tabToPart.forEach((tab, candidate) -> {
                if (candidate == part && !tab.equals(tabs.getSelectedTab())) {
                    tabs.setSelectedTab(tab);
                }
            });
        }
    }
}
