package com.github.enerccio.joi.ui.main;

import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.jobs.AssistantsRunsGrid;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.bound.SessionManager;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.ui.jobs.ActiveJobsGrid;
import com.github.enerccio.marginalia.ui.main.LoginCheckRoute;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * {@code /jobs}: the jobs that run now (the platform's list), and under it what each assistant does now (her turn, summary jobs and
 * tasks), for an administrator who wants to see at a glance whether the server is busy. It logs in like the other pages and shows
 * nothing to other users; the history of the jobs is in the Administration part.
 * <p>
 * The platform has a page of the same address (it names Marginalia and lists runs on books). This one takes its place:
 * {@link JoiServlet} registers it, it is not annotated with a route of its own.
 */
@Configurable(preConstruction = true)
public class JoiJobsView extends LoginCheckRoute {

    /** The address of the page. */
    public static final String PATH = "jobs";

    @Autowired
    private User user;

    @Autowired
    private AdminGuard adminGuard;

    @Autowired
    private SessionManager sessionManager;

    public JoiJobsView() {
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

            removeAll();
            VerticalLayout page = new VerticalLayout();
            page.setSizeFull();
            if (adminGuard.isAdmin()) {
                page.add(new H3(loc.getValue(L.LABEL_ACTIVE_JOBS)), new ActiveJobsGrid().create(),
                        new H3(loc.getValue(J_L.LABEL_ASSISTANTS_NOW)), new AssistantsRunsGrid().create());
            } else {
                Span denied = new Span(loc.getValue(L.MSG_ADMIN_ONLY));
                denied.addClassName(CssStyles.TEXT_SECONDARY);
                page.add(denied);
            }
            add(page);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }
}
