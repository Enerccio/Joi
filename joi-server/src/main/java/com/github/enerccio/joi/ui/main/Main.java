package com.github.enerccio.joi.ui.main;

import com.github.enerccio.joi.ui.access.DeviceApprovalDialog;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import java.util.List;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.workspace.JoiWorkspace;
import com.github.enerccio.marginalia.bound.ApplicationPoint;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.settings.BaseUserSetting;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.ui.main.LoginCheckRoute;
import com.github.enerccio.marginalia.ui.widgets.ThemeSwitch;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * The root page: the platform's login (or the first user on an empty database), then the {@link JoiWorkspace}.
 */
@Route("/")
@Configurable(preConstruction = true)
public class Main extends LoginCheckRoute implements BeforeEnterObserver {

    @Autowired
    private User user;

    @Autowired
    private SessionPoint sessionPoint;

    @Autowired
    private ApplicationPoint applicationPoint;

    @Autowired
    private SettingService settingService;

    /** The code of {@code /?device=<code>} (the link an OAuth device shows), until the user is logged in. */
    private String pendingDevice;
    private boolean loggedIn;

    public Main() {
        showLogin();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> device = event.getLocation().getQueryParameters().getParameters().get("device");
        if (device == null) {
            return;
        }
        pendingDevice = device.isEmpty() ? "" : device.getFirst();
        if (loggedIn) {
            openDeviceApproval();
        }
    }

    /** Asks the user to approve the device of the link. */
    private void openDeviceApproval() {
        String code = pendingDevice;
        pendingDevice = null;
        DeviceApprovalDialog dialog = new DeviceApprovalDialog(code, null);
        dialog.create();
        dialog.open();
        // the code is not kept in the address: a reload must not ask again
        UI.getCurrent().getPage().getHistory().replaceState(null, "");
    }

    @Override
    protected String getAppTitle() {
        return "Joi";
    }

    @Override
    protected String getAppDescription() {
        return loc.getValue(J_L.LABEL_APP_DESC);
    }

    @Override
    protected String authenticatedSessionUser() {
        return user.getId() == null ? null : user.getLogin();
    }

    @Override
    protected boolean authenticate(String userName, String password) throws Exception {
        return userService.authenticate(userName, password, clientAddress());
    }

    @Override
    protected void proceedWithLogin(String username) {
        try {
            User u = userService.findByName(username);
            user.setId(u.getId());
            user.setLogin(u.getLogin());
            user.setFullName(u.getFullName());
            sessionManager.userLoggedIn(user, VaadinSession.getCurrent());

            BaseUserSetting setting = settingService.getUserSetting();
            sessionPoint.setLanguage(loc.resolveLanguage(setting.getLanguage()));
            ThemeSwitch.apply(setting.getThemeMode());

            JoiWorkspace workspace = new JoiWorkspace();
            // the registry keeps its own copy: the session bean is a proxy of the request's session
            User referenceCopy = new User();
            referenceCopy.setId(u.getId());
            referenceCopy.setLogin(u.getLogin());
            referenceCopy.setFullName(u.getFullName());
            applicationPoint.register(UI.getCurrent(), workspace, referenceCopy);
            sessionPoint.setWorkspace(workspace);
            workspace.setOnLogout(this::logout);
            add(workspace.create());
            workspace.refresh();
            workspace.restoreAutosaved();
            loggedIn = true;
            if (pendingDevice != null) {
                openDeviceApproval();
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }
}
