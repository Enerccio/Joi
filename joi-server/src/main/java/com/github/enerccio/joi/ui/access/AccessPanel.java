package com.github.enerccio.joi.ui.access;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.api.ApiAccess;
import com.github.enerccio.joi.domain.collections.AccessTokenKind;
import com.github.enerccio.joi.domain.model.impl.AccessToken;
import com.github.enerccio.joi.domain.service.AccessTokenService;
import com.github.enerccio.joi.domain.service.ApiAccessService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.AdminScope;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.JavascriptLibrary;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * User Settings → Access: the tokens and the devices that may use the API as the user (like Marginalia's API keys),
 * with <i>Create token</i> and <i>Connect a device</i> when the administrator offers them. An administrator may give a token
 * the administration scopes of the API ({@link AdminScope}). Each change is stored at once.
 */
@Configurable
public class AccessPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private ApiAccessService apiAccessService;

    @Autowired
    private AdminGuard adminGuard;

    private Grid<AccessToken> grid;
    private Button createButton;
    private Button connectButton;
    private Button pairPhoneButton;
    private Span offSpan;

    public Component create() throws Exception {
        Span info = new Span(String.format(loc.getValue(J_L.HELP_USER_ACCESS), apiUrl()));
        info.addClassName(CssStyles.HINT_TEXT);
        offSpan = new Span(loc.getValue(J_L.MSG_API_OFF));
        offSpan.addClassName(CssStyles.TEXT_ERROR);

        createButton = new Button(loc.getValue(J_L.LABEL_CREATE_TOKEN), Solid.PLUS.create(), e -> openCreateDialog());
        connectButton = new Button(loc.getValue(J_L.LABEL_CONNECT_DEVICE), Solid.LINK.create(), e -> {
            DeviceApprovalDialog dialog = new DeviceApprovalDialog(null, this::refreshQuietly);
            dialog.create();
            dialog.open();
        });

        pairPhoneButton = new Button(loc.getValue(J_L.LABEL_PAIR_PHONE), Solid.MOBILE_SCREEN.create(), e -> {
            PairPhoneDialog dialog = new PairPhoneDialog(this::refreshQuietly);
            dialog.create();
            dialog.open();
        });

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        String never = loc.getValue(L.LABEL_NEVER);
        grid = new Grid<>(AccessToken.class, false);
        grid.setWidthFull();
        grid.setAllRowsVisible(true);
        grid.addColumn(AccessToken::getName).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        grid.addColumn(t -> loc.getValue(switch (t.getKind()) {
                    case OAUTH -> J_L.ENUM_ACCESS_TOKEN_OAUTH;
                    case LOCAL -> J_L.ENUM_ACCESS_TOKEN_LOCAL;
                    case TOKEN -> J_L.ENUM_ACCESS_TOKEN_TOKEN;
                }))
                .setHeader(loc.getValue(L.LABEL_TYPE)).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.getScopes().stream().map(this::scopeLabel).collect(Collectors.joining(", ")))
                .setHeader(loc.getValue(L.LABEL_ACCESS)).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> date(format, t.getCreation(), ""))
                .setHeader(loc.getValue(L.LABEL_CREATED)).setWidth(UIConstants.DATE_COLUMN_SIZE).setFlexGrow(0);
        // a device's access token expires all the time: what counts is its refresh token
        grid.addColumn(t -> date(format, t.getKind() == AccessTokenKind.OAUTH ? t.getRefreshExpires() : t.getExpires(), never))
                .setHeader(loc.getValue(L.LABEL_EXPIRES)).setWidth(UIConstants.DATE_COLUMN_SIZE).setFlexGrow(0);
        grid.addColumn(t -> date(format, t.getLastUsed(), never))
                .setHeader(loc.getValue(L.LABEL_LAST_USED)).setWidth(UIConstants.DATE_COLUMN_SIZE).setFlexGrow(0);
        grid.addComponentColumn(token -> {
                    Button revoke = new Button(Solid.TRASH.create(), e -> ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> revoke(token)));
                    revoke.setThemeName("error tertiary");
                    revoke.setTooltipText(loc.getValue(L.LABEL_REVOKE));
                    revoke.setAriaLabel(loc.getValue(L.LABEL_REVOKE));
                    return revoke;
                })
                .setHeader("").setWidth(UIConstants.TOOL_COLUMN_SIZE).setFlexGrow(0);

        VerticalLayout layout = new VerticalLayout(info, offSpan, new HorizontalLayout(createButton, pairPhoneButton, connectButton), grid);
        layout.setWidthFull();
        refresh();
        return layout;
    }

    public void refresh() throws Exception {
        ApiAccess access = apiAccessService.get();
        createButton.setVisible(access.tokens());
        connectButton.setVisible(access.oauth());
        pairPhoneButton.setVisible(access.oauth());
        offSpan.setVisible(!access.tokens() && !access.oauth());
        List<AccessToken> tokens = new ArrayList<>(accessTokenService.findAllForUser());
        tokens.removeIf(AccessToken::isDeleted);
        tokens.sort(Comparator.comparing(AccessToken::getCreation, Comparator.nullsLast(Comparator.reverseOrder())));
        grid.setItems(tokens);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void revoke(AccessToken token) {
        try {
            accessTokenService.revoke(token.getUuid());
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** Asks for the name and the expiry of a new token. */
    void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(loc.getValue(J_L.LABEL_CREATE_TOKEN));
        TextField name = new TextField(loc.getValue(L.LABEL_NAME));
        name.setWidthFull();
        name.setRequired(true);
        IntegerField days = new IntegerField(loc.getValue(L.LABEL_EXPIRES_AFTER_DAYS));
        days.setMin(1);
        days.setHelperText(loc.getValue(L.HELP_EXPIRES_AFTER_DAYS));
        CheckboxGroup<AdminScope> scopes = new CheckboxGroup<>(loc.getValue(L.LABEL_ACCESS));
        scopes.addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL);
        scopes.setItems(Arrays.asList(AdminScope.values()));
        scopes.setItemLabelGenerator(this::scopeLabel);
        scopes.setHelperText(loc.getValue(J_L.HELP_TOKEN_SCOPES));
        scopes.setVisible(isAdmin());
        Button create = new Button(loc.getValue(J_L.LABEL_CREATE_TOKEN), e -> {
            try {
                Date expires = days.getValue() == null ? null : new Date(System.currentTimeMillis() + days.getValue() * 24L * 3600 * 1000);
                AccessTokenService.Created created = accessTokenService.create(name.getValue(), expires, scopes.getValue());
                dialog.close();
                refresh();
                showCreated(created.text());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                Notification.warning(ex.getMessage());
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        create.setThemeName("primary");
        VerticalLayout content = new VerticalLayout(name, days, scopes);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(new Button(loc.getValue(L.LABEL_CANCEL), e -> dialog.close()), create);
        DialogFocus.onOpen(dialog, name);
        DialogShortcuts.submitWith(create, name, days);
        dialog.open();
    }

    private String scopeLabel(AdminScope scope) {
        return loc.getValue(switch (scope) {
            case UPDATE -> L.ENUM_API_SCOPE_UPDATE;
            case EXTENSIONS -> L.ENUM_API_SCOPE_EXTENSIONS;
        });
    }

    private boolean isAdmin() {
        try {
            return adminGuard.isAdmin();
        } catch (Exception e) {
            return false;
        }
    }

    /** The text of a new token, shown once. */
    private void showCreated(String text) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(loc.getValue(J_L.LABEL_TOKEN));
        dialog.setWidth("min(40em, 95vw)");
        TextField token = new TextField(loc.getValue(J_L.LABEL_TOKEN));
        token.setValue(text);
        token.setReadOnly(true);
        token.setWidthFull();
        Button copy = new Button(loc.getValue(L.LABEL_COPY), Solid.COPY.create(),
                e -> JavascriptLibrary.copyToClipboard(e.getSource().getUI().orElseThrow().getPage(), text));
        Button close = new Button(loc.getValue(L.LABEL_CLOSE), e -> dialog.close());
        close.setThemeName("primary");
        VerticalLayout content = new VerticalLayout(new Span(loc.getValue(J_L.MSG_TOKEN_CREATED)), token);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(copy, close);
        dialog.open();
    }

    /** Where the API is, as the browser reaches the server. */
    private static String apiUrl() {
        VaadinRequest request = VaadinRequest.getCurrent();
        UI ui = UI.getCurrent();
        if (request == null || ui == null) {
            return JoiProtocol.API;
        }
        String host = request.getHeader("Host");
        return host == null ? JoiProtocol.API : (request.isSecure() ? "https://" : "http://") + host + request.getContextPath() + JoiProtocol.API;
    }

    private static String date(DateFormat format, Date date, String none) {
        return date == null ? none : format.format(date);
    }

    /** The grid, for tests. */
    public Grid<AccessToken> getGrid() {
        return grid;
    }

    public Button getCreateButton() {
        return createButton;
    }

    public Button getPairPhoneButton() {
        return pairPhoneButton;
    }

    public Button getConnectButton() {
        return connectButton;
    }
}
