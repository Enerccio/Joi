package com.github.enerccio.joi.ui.main;

import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.settings.BaseUserSetting;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.ui.main.LoginCheckRoute;
import com.github.enerccio.marginalia.ui.widgets.ThemeSwitch;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * The root page: the platform's login (or the first user on an empty database), then Joi. For now only a placeholder
 * after the login.
 */
@Route("/")
@Configurable(preConstruction = true)
public class Main extends LoginCheckRoute {

    @Autowired
    private User user;

    @Autowired
    private SessionPoint sessionPoint;

    @Autowired
    private SettingService settingService;

    public Main() {
        showLogin();
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

            Div home = new Div();
            home.addClassName(JoiCssStyles.JOI_HOME);
            home.add(new Span(String.format(loc.getValue(J_L.MSG_SIGNED_IN), u.getLogin())));
            home.add(new Button(loc.getValue(J_L.LABEL_LOGOUT), e -> logout()));
            add(home);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }
}
