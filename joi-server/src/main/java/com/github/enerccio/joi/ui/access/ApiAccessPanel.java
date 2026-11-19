package com.github.enerccio.joi.ui.access;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.api.ApiAccess;
import com.github.enerccio.joi.api.ApiConfiguration;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * Administration → API access: which ways nodes and other clients may sign in (tokens, OAuth devices) and how long the
 * OAuth tokens live. No auth is not here: it is the configuration's ({@code joi.api.no-auth}), shown when it is on.
 */
@Configurable
public class ApiAccessPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private ApiAccessService apiAccessService;

    @Autowired
    private ApiConfiguration configuration;

    private Checkbox tokens;
    private Checkbox oauth;
    private IntegerField accessMinutes;
    private IntegerField refreshDays;

    public Component create() throws Exception {
        Span help = new Span(loc.getValue(J_L.HELP_API_ACCESS));
        help.addClassName(CssStyles.HINT_TEXT);
        Span noAuth = new Span(String.format(loc.getValue(J_L.HELP_NO_AUTH),
                StringUtils.defaultIfBlank(configuration.getNoAuthUser(), loc.getValue(J_L.LABEL_FIRST_ADMINISTRATOR))));
        noAuth.addClassName(CssStyles.TEXT_ERROR);
        noAuth.setVisible(configuration.isNoAuth());

        tokens = new Checkbox(loc.getValue(J_L.LABEL_ALLOW_TOKENS));
        oauth = new Checkbox(loc.getValue(J_L.LABEL_ALLOW_OAUTH));
        accessMinutes = new IntegerField(loc.getValue(J_L.LABEL_ACCESS_TOKEN_MINUTES));
        accessMinutes.setMin(5);
        accessMinutes.setStepButtonsVisible(true);
        refreshDays = new IntegerField(loc.getValue(J_L.LABEL_REFRESH_TOKEN_DAYS));
        refreshDays.setMin(1);
        refreshDays.setStepButtonsVisible(true);
        oauth.addValueChangeListener(e -> {
            accessMinutes.setEnabled(e.getValue());
            refreshDays.setEnabled(e.getValue());
        });

        FormLayout form = new FormLayout(tokens, oauth, accessMinutes, refreshDays);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));

        Button save = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), e -> save());
        save.setThemeName("primary");

        load();
        VerticalLayout layout = new VerticalLayout(help, noAuth, form, save);
        layout.setWidthFull();
        return layout;
    }

    private void load() throws Exception {
        ApiAccess access = apiAccessService.get();
        tokens.setValue(access.tokens());
        oauth.setValue(access.oauth());
        accessMinutes.setValue(access.accessTokenMinutes());
        refreshDays.setValue(access.refreshTokenDays());
        accessMinutes.setEnabled(access.oauth());
        refreshDays.setEnabled(access.oauth());
    }

    void save() {
        try {
            ApiAccess before = apiAccessService.get();
            apiAccessService.save(new ApiAccess(tokens.getValue(), oauth.getValue(),
                    accessMinutes.getValue() == null ? before.accessTokenMinutes() : accessMinutes.getValue(),
                    refreshDays.getValue() == null ? before.refreshTokenDays() : refreshDays.getValue()));
            load();
            Notification.success(loc.getValue(J_L.MSG_SETTINGS_SAVED));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // for tests

    public Checkbox getTokens() {
        return tokens;
    }

    public Checkbox getOauth() {
        return oauth;
    }
}
