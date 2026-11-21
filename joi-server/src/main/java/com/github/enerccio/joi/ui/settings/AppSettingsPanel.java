package com.github.enerccio.joi.ui.settings;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.settings.JoiAppSettings;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/** Administration → App settings: what the administrator decides for everybody ({@link JoiAppSettings}). */
@Configurable
public class AppSettingsPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiAppSettingsService appSettingsService;

    private Checkbox usersMayUseLocalServers;

    public Component create() throws Exception {
        Span help = new Span(loc.getValue(J_L.HELP_APP_SETTINGS));
        help.addClassName(CssStyles.HINT_TEXT);

        usersMayUseLocalServers = new Checkbox(loc.getValue(J_L.LABEL_USERS_MAY_USE_LOCAL_SERVERS));
        usersMayUseLocalServers.setHelperText(loc.getValue(J_L.HELP_USERS_MAY_USE_LOCAL_SERVERS));

        Button save = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), e -> save());
        save.setThemeName("primary");

        refresh();
        VerticalLayout layout = new VerticalLayout(help, usersMayUseLocalServers, save);
        layout.setWidthFull();
        return layout;
    }

    /** Shows what is stored. */
    public void refresh() throws Exception {
        if (usersMayUseLocalServers != null) {
            usersMayUseLocalServers.setValue(appSettingsService.get().usersMayUseLocalServers());
        }
    }

    /** Stores what is shown (the Save button; public for the tests of the administration). */
    public void save() {
        try {
            appSettingsService.save(new JoiAppSettings(usersMayUseLocalServers.getValue()));
            refresh();
            Notification.success(loc.getValue(J_L.MSG_SETTINGS_SAVED));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // for tests

    public Checkbox getUsersMayUseLocalServers() {
        return usersMayUseLocalServers;
    }
}
