package com.github.enerccio.joi.ui.access;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.DeviceAuthorizationService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.ui.widgets.DialogShortcuts;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * Connects a device to the user's account (the OAuth device grant): the user types the code the device shows (or opens
 * the link the device shows, {@code /?device=<code>}, which fills it in), sees which device it is, and approves or
 * denies it. The device then gets its tokens at its next poll.
 */
@Configurable
public class DeviceApprovalDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private DeviceAuthorizationService deviceAuthorizationService;

    private final String initialCode;
    private final Runnable onApproved;
    private TextField codeField;
    private Span who;
    private Button approveButton;
    private Button denyButton;

    /**
     * @param code       the code from the link, null for none
     * @param onApproved called after an approval (a list of the devices refreshes), may be null
     */
    public DeviceApprovalDialog(String code, Runnable onApproved) {
        this.initialCode = code;
        this.onApproved = onApproved;
    }

    public void create() {
        setHeaderTitle(loc.getValue(J_L.LABEL_CONNECT_DEVICE));
        setWidth("min(30em, 95vw)");
        codeField = new TextField(loc.getValue(J_L.LABEL_DEVICE_CODE));
        codeField.setHelperText(loc.getValue(J_L.HELP_DEVICE_CODE));
        codeField.setWidthFull();
        codeField.setValueChangeMode(ValueChangeMode.EAGER);
        codeField.addValueChangeListener(e -> lookUp());
        who = new Span();
        who.addClassName(CssStyles.HINT_TEXT);

        approveButton = new Button(loc.getValue(J_L.LABEL_APPROVE), Solid.CHECK.create(), e -> approve());
        approveButton.setThemeName("primary");
        denyButton = new Button(loc.getValue(J_L.LABEL_DENY), Solid.BAN.create(), e -> deny());
        denyButton.setThemeName("error tertiary");
        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> close());

        VerticalLayout content = new VerticalLayout(codeField, who);
        content.setPadding(false);
        add(content);
        getFooter().add(denyButton, cancel, approveButton);
        DialogFocus.onOpen(this, codeField);
        DialogShortcuts.submitWith(approveButton, codeField);
        codeField.setValue(StringUtils.defaultString(initialCode));
        lookUp();
    }

    private void lookUp() {
        DeviceAuthorizationService.Request request = deviceAuthorizationService.find(codeField.getValue());
        boolean typed = codeField.getValue().replaceAll("[^A-Za-z]", "").length() >= 8;
        who.setText(request != null ? String.format(loc.getValue(J_L.MSG_DEVICE_WANTS), request.clientName())
                : typed ? loc.getValue(J_L.MSG_DEVICE_UNKNOWN) : "");
        approveButton.setEnabled(request != null);
        denyButton.setEnabled(request != null);
    }

    private void approve() {
        if (deviceAuthorizationService.find(codeField.getValue()) == null) {
            lookUp();
            return;
        }
        try {
            deviceAuthorizationService.approve(codeField.getValue());
            Notification.success(loc.getValue(J_L.MSG_DEVICE_APPROVED));
            close();
            if (onApproved != null) {
                onApproved.run();
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Notification.warning(e.getMessage());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void deny() {
        deviceAuthorizationService.deny(codeField.getValue());
        close();
    }

    // for tests

    public TextField getCodeField() {
        return codeField;
    }

    public Span getWho() {
        return who;
    }

    public Button getApproveButton() {
        return approveButton;
    }
}
