package com.github.enerccio.joi.ui.workspace;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.extensions.JoiExtensions;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.workspace.parts.*;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
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
 * Joi's page after the login: the parts as tabs on the left, under them the Administration button (for
 * administrators; it shows the admin part in place of the tabs' content, as Marginalia's workspace does) and the account
 * actions (language, theme, password, logout). Its {@link com.github.enerccio.marginalia.ui.Restorable} state is the selected tab and the state of the
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

    @Autowired
    private AdminGuard adminGuard;

    @Autowired
    private JoiExtensions joiExtensions;

    private HTabSheet tabs;

    // the parts under the keys of the stored state, in the order of the tabs; a key never changes. The admin part is
    // added in create(), for administrators only (asked of AdminGuard: the session's user does not carry the flag); it
    // has no tab but the button above the account actions.
    private final Map<String, WorkspacePart> partsByKey = new LinkedHashMap<>();
    {
        partsByKey.put("jois", new JoisPart());
        partsByKey.put("bodies", new BodiesPart());
        partsByKey.put("animations", new AnimationsPart());
        partsByKey.put("animation-packs", new AnimationPacksPart());
        partsByKey.put("models", new AIPart());
        partsByKey.put("trash", new TrashPart(this));
        partsByKey.put("settings", new UserSettingsPart(this));
    }

    private final Map<Tab, WorkspacePart> tabToPart = new HashMap<>();
    private final Map<WorkspacePart, Component> partComponents = new LinkedHashMap<>();
    private final WorkspaceAutosave autosave = new WorkspaceAutosave(this);

    private WorkspacePart activePart;
    private AdminPart adminPart;
    private Component adminComponent;
    private Button adminButton;
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

        addTab("jois", PartIcon.JOI, J_L.LABEL_JOIS);
        addTab("bodies", PartIcon.BODIES, J_L.LABEL_BODIES);
        addTab("animations", PartIcon.ANIMATIONS, J_L.LABEL_ANIMATIONS);
        addTab("animation-packs", PartIcon.ANIMATION_PACKS, J_L.LABEL_ANIMATION_PACKS);
        addTab("models", PartIcon.MODELS, J_L.LABEL_MODELS);
        addTab("trash", PartIcon.TRASH, L.LABEL_TRASH);
        addTab("settings", PartIcon.SETTINGS, J_L.LABEL_USER_SETTINGS);
        // the tabs of the extensions that are loaded now, after the settings; one loaded later shows in the next workspace
        for (JoiExtensions.Part extension : joiExtensions.parts()) {
            WorkspacePart part = new ExtensionTab(extension.part());
            partsByKey.put(extension.key(), part);
            Component content = part.create();
            partComponents.put(part, content);
            HorizontalLayout header = new HorizontalLayout(PartIcon.EXTENSION.create(), new Span(extension.part().caption()));
            header.setAlignItems(Alignment.CENTER);
            header.setSpacing(true);
            tabToPart.put(tabs.add(header, content), part);
        }
        if (adminGuard.isAdmin()) {
            adminPart = new AdminPart();
            partsByKey.put("admin", adminPart);
            adminComponent = adminPart.create();
            partComponents.put(adminPart, adminComponent);
        }

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

        if (adminPart != null) {
            adminButton = new Button(loc.getValue(J_L.LABEL_ADMIN), PartIcon.ADMIN.create());
            adminButton.setWidthFull();
            adminButton.addClickListener(e -> openAdmin());
            footer.add(adminButton);
        }

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

    /**
     * Shows the admin part in place of the selected tab, what the Administration button does.
     */
    public void openAdmin() {
        if (adminPart == null || activePart == adminPart) {
            return;
        }
        // deselects the tab: the listener closes the part of the tab
        tabs.setCustomContent(adminComponent);
        activePart = adminPart;
        try {
            adminPart.onTabSwitched();
        } catch (Exception ex) {
            UIUtils.internalServerError(loc, ex);
        }
        autosave();
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
        for (HasEnabled control : new HasEnabled[]{adminButton, languageSelector, changePasswordButton, logoutButton}) {
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
     * The part stored under the key ({@code "jois"}, {@code "models"}, {@code "settings"}, {@code "admin"}), null for an unknown key (or the admin part of a user
     * who is not an administrator).
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
        WorkspacePart selected = activePart;
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
        if (part != null && part == adminPart) {
            openAdmin();
        } else if (part != null) {
            tabToPart.forEach((tab, candidate) -> {
                if (candidate == part && !tab.equals(tabs.getSelectedTab())) {
                    tabs.setSelectedTab(tab);
                }
            });
        }
    }
}
