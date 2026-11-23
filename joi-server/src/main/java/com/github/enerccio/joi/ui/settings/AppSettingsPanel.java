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
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.textfield.IntegerField;
import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;
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
    private IntegerField maxFileKb;
    private IntegerField maxTotalKb;
    private IntegerField maxFiles;
    private IntegerField maxImageKb;
    private IntegerField maxArchiveKb;

    public Component create() throws Exception {
        Span help = new Span(loc.getValue(J_L.HELP_APP_SETTINGS));
        help.addClassName(CssStyles.HINT_TEXT);

        usersMayUseLocalServers = new Checkbox(loc.getValue(J_L.LABEL_USERS_MAY_USE_LOCAL_SERVERS));
        usersMayUseLocalServers.setHelperText(loc.getValue(J_L.HELP_USERS_MAY_USE_LOCAL_SERVERS));

        H4 workspaceTitle = new H4(loc.getValue(J_L.LABEL_WORKSPACE_LIMITS));
        Span workspaceHelp = new Span(loc.getValue(J_L.HELP_WORKSPACE_LIMITS));
        workspaceHelp.addClassName(CssStyles.HINT_TEXT);
        maxFileKb = limit(J_L.LABEL_WORKSPACE_MAX_FILE);
        maxTotalKb = limit(J_L.LABEL_WORKSPACE_MAX_TOTAL);
        maxFiles = limit(J_L.LABEL_WORKSPACE_MAX_FILES);
        maxImageKb = limit(J_L.LABEL_WORKSPACE_MAX_IMAGE);
        maxArchiveKb = limit(J_L.LABEL_WORKSPACE_MAX_ARCHIVE);

        Button save = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), e -> save());
        save.setThemeName("primary");

        refresh();
        VerticalLayout layout = new VerticalLayout(help, usersMayUseLocalServers, workspaceTitle, workspaceHelp, maxFileKb, maxTotalKb,
                maxFiles, maxImageKb, maxArchiveKb, save);
        layout.setWidthFull();
        return layout;
    }

    private IntegerField limit(J_L label) {
        IntegerField field = new IntegerField(loc.getValue(label));
        field.setMin(0);
        field.setStepButtonsVisible(true);
        field.setWidth("20em");
        return field;
    }

    /** Shows what is stored: the limits as they are now (the defaults for what was never set). */
    public void refresh() throws Exception {
        if (usersMayUseLocalServers != null) {
            JoiAppSettings stored = appSettingsService.get();
            usersMayUseLocalServers.setValue(stored.usersMayUseLocalServers());
            WorkspaceLimits limits = stored.workspaceLimits();
            maxFileKb.setValue(kilobytes(limits.maxFileBytes()));
            maxTotalKb.setValue(kilobytes(limits.maxTotalBytes()));
            maxFiles.setValue(limits.maxFiles());
            maxImageKb.setValue(kilobytes(limits.maxImageBytes()));
            maxArchiveKb.setValue(kilobytes(limits.maxArchiveBytes()));
        }
    }

    private static int kilobytes(long bytes) {
        return (int) Math.min(Integer.MAX_VALUE, bytes / 1024);
    }

    private static long bytes(IntegerField field) {
        return field.getValue() == null ? 0 : field.getValue() * 1024L;
    }

    /** Stores what is shown (the Save button; public for the tests of the administration). */
    public void save() {
        try {
            appSettingsService.save(new JoiAppSettings(usersMayUseLocalServers.getValue(), bytes(maxFileKb), bytes(maxTotalKb),
                    maxFiles.getValue() == null ? 0 : maxFiles.getValue(), bytes(maxImageKb), bytes(maxArchiveKb)));
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

    public IntegerField getMaxFileKb() {
        return maxFileKb;
    }

    public IntegerField getMaxFiles() {
        return maxFiles;
    }
}
