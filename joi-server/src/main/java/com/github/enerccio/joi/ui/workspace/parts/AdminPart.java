package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.ui.access.ApiAccessPanel;
import com.github.enerccio.joi.ui.tools.ToolsPanel;
import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.dialogs.UserDialog;
import com.github.enerccio.marginalia.ui.dialogs.permissions.PermissionsDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.workspace.parts.admin.CleanupPanel;
import com.github.enerccio.marginalia.ui.workspace.parts.admin.DatabaseBackupPanel;
import com.github.enerccio.marginalia.ui.workspace.parts.admin.GpuPanel;
import com.github.enerccio.marginalia.ui.workspace.parts.admin.ResourceGarbagePanel;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The administration, for administrators only (the workspace adds it for them): the users of the installation and the GPUs with
 * their locks (the platform's {@link GpuPanel}). The users tab follows the one of Marginalia's admin page.
 */
@Configurable
public class AdminPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    private final GpuPanel gpuPanel = new GpuPanel();
    private final ApiAccessPanel apiAccessPanel = new ApiAccessPanel();
    private final ToolsPanel toolsPanel = new ToolsPanel(ToolScope.APP);
    // the platform's panels, as in Marginalia's administration: backups of the database, the cleanup of what users
    // deleted (the trash of all of them, deleted for good), and the sweep of the files nothing points to
    private final DatabaseBackupPanel databaseBackupPanel = new DatabaseBackupPanel();
    private final CleanupPanel cleanupPanel = new CleanupPanel();
    private final ResourceGarbagePanel resourceGarbagePanel = new ResourceGarbagePanel();
    private Grid<User> usersGrid;
    private Button addUserButton;
    private TabSheet tabs;

    @Override
    public Component create() throws Exception {
        tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add(loc.getValue(J_L.LABEL_USERS), createUsers());
        tabs.add(loc.getValue(L.LABEL_GPUS), gpuPanel.create());
        tabs.add(loc.getValue(J_L.LABEL_API_ACCESS), apiAccessPanel.create());
        tabs.add(loc.getValue(J_L.LABEL_TOOLS), toolsPanel.create());
        tabs.add(loc.getValue(J_L.LABEL_DATABASE_BACKUPS), databaseBackupPanel.create());
        tabs.add(loc.getValue(J_L.LABEL_CLEANUP), cleanupPanel.create());
        tabs.add(loc.getValue(J_L.LABEL_RESOURCE_FILES), resourceGarbagePanel.create());
        return tabs;
    }

    private Component createUsers() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        addUserButton = new Button(loc.getValue(J_L.LABEL_ADD_USER), Solid.PLUS_CIRCLE.create(), e -> openUserDialog(new User()));
        addUserButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(addUserButton);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        usersGrid = new Grid<>(User.class, false);
        usersGrid.setSizeFull();
        usersGrid.addColumn(User::getLogin)
                .setHeader(loc.getValue(L.LABEL_USERNAME))
                .setFlexGrow(1);
        usersGrid.addColumn(User::getFullName)
                .setHeader(loc.getValue(L.LABEL_USER_FULLNAME))
                .setFlexGrow(1);
        usersGrid.addColumn(user -> loc.getValue(user.isAdmin() ? L.LABEL_YES : L.LABEL_NO))
                .setHeader(loc.getValue(L.LABEL_ADMINISTRATOR))
                .setFlexGrow(0)
                .setWidth("150px");
        usersGrid.addComponentColumn(this::userActions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_GIANT);

        layout.add(header, usersGrid);
        layout.setFlexGrow(1, usersGrid);
        return layout;
    }

    /** Edit, permissions and delete (not for the user themself). */
    private Component userActions(User user) {
        Button editButton = new Button(Solid.PEN.create(), e -> openUserDialog(user));
        editButton.setTooltipText(loc.getValue(L.LABEL_EDIT));

        Button permissionsButton = new Button(Solid.KEY.create(), e -> {
            PermissionsDialog dialog = new PermissionsDialog(user);
            dialog.create();
            dialog.open();
        });
        permissionsButton.setTooltipText(loc.getValue(L.LABEL_PERMISSIONS));

        Button deleteButton = new Button(Solid.TRASH.create(), e -> deleteUser(user));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));
        deleteButton.setEnabled(!user.getId().equals(currentUser.getId()));

        HorizontalLayout actions = new HorizontalLayout(editButton, permissionsButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void openUserDialog(User user) {
        UserDialog dialog = new UserDialog(user, false);
        dialog.setOnSave(this::refreshQuietly);
        dialog.create();
        dialog.open();
    }

    private void deleteUser(User user) {
        try {
            if (user.getId().equals(currentUser.getId())) {
                Notification.warning(loc.getValue(J_L.MSG_CANNOT_DELETE_SELF));
                return;
            }
            if (userService.isLastAdmin(user)) {
                Notification.warning(loc.getValue(L.MSG_CANNOT_DELETE_LAST_ADMIN));
                return;
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
            try {
                userService.deleteUser(user);
                refresh();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        });
    }

    @Override
    public void refresh() throws Exception {
        if (usersGrid == null) {
            return;
        }
        List<User> users = userService.findAll();
        users.sort(Comparator.comparing(User::getLogin, String.CASE_INSENSITIVE_ORDER));
        usersGrid.setItems(users);
        gpuPanel.refresh();
        toolsPanel.refresh();
        databaseBackupPanel.refresh();
        cleanupPanel.refresh();
        resourceGarbagePanel.refresh();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /**
     * The grid of the users, for tests.
     */
    Grid<User> getUsersGrid() {
        return usersGrid;
    }

    @Override
    public List<LLMInteract> getLLMChildren(boolean deep) {
        return List.of(gpuPanel, databaseBackupPanel, cleanupPanel, resourceGarbagePanel);
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addUserButton);
    }
}
