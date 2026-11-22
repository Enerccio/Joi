package com.github.enerccio.joi.ui.access;

import com.github.enerccio.joi.api.ApiConfiguration;
import com.github.enerccio.joi.domain.service.DeviceAuthorizationService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.shared.Registration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * Pairs a phone without a code to type: the dialog shows a QR code of {@code joi://pair?server=...&offer=...}, which the
 * phone's camera opens in the Joi app. The app asks for codes with the offer ({@link DeviceAuthorizationService#createOffer}),
 * and this dialog, which polls for such a device, approves it as the user (the approval is the user's own, so it runs
 * here, not on the request of the phone). The offer lives while the dialog is open, and for ten minutes at most.
 */
@Configurable
public class PairPhoneDialog extends Dialog {

    /** The scheme of the app. */
    static final String SCHEME = "joi";
    private static final int POLL_MILLIS = 1500;
    private static final int QR_SIZE = 280;

    @Autowired
    private Localization loc;

    @Autowired
    private DeviceAuthorizationService deviceAuthorizationService;

    @Autowired
    private ApiConfiguration configuration;

    private final Runnable onApproved;
    private String offer;
    private TextField serverField;
    private Span serverHelp;
    private Image qr;
    private TextField link;
    private Span status;
    private Registration poll;

    /** @param onApproved called after the phone is connected (the list of devices refreshes), may be null */
    public PairPhoneDialog(Runnable onApproved) {
        this.onApproved = onApproved;
    }

    public void create() {
        setHeaderTitle(loc.getValue(J_L.LABEL_PAIR_PHONE));
        setWidth("min(26em, 95vw)");
        offer = deviceAuthorizationService.createOffer();

        Span help = new Span(loc.getValue(J_L.HELP_PAIR_PHONE));
        help.addClassName(CssStyles.HINT_TEXT);
        qr = new Image();
        qr.setWidth(QR_SIZE + "px");
        qr.setHeight(QR_SIZE + "px");
        qr.addClassName(JoiCssStyles.PAIR_QR);
        serverField = new TextField(loc.getValue(J_L.LABEL_PHONE_SERVER));
        serverField.setHelperText(loc.getValue(J_L.HELP_PHONE_SERVER));
        serverField.setWidthFull();
        serverField.setValueChangeMode(ValueChangeMode.LAZY);
        serverField.addValueChangeListener(e -> update());
        serverHelp = new Span();
        serverHelp.addClassName(CssStyles.TEXT_ERROR);
        link = new TextField(loc.getValue(J_L.LABEL_PHONE_LINK));
        link.setReadOnly(true);
        link.setWidthFull();
        status = new Span(loc.getValue(J_L.MSG_PHONE_WAITING));
        status.addClassName(CssStyles.HINT_TEXT);

        VerticalLayout content = new VerticalLayout(help, serverField, serverHelp, qr, status, link);
        content.setPadding(false);
        content.setAlignItems(FlexComponent.Alignment.CENTER);
        add(content);
        Button close = new Button(loc.getValue(L.LABEL_CLOSE), e -> close());
        getFooter().add(close);

        serverField.setValue(defaultServer());
        update();
        addOpenedChangeListener(e -> {
            if (e.isOpened()) {
                startPolling();
            } else {
                stopPolling();
            }
        });
    }

    /** The server as the phone should reach it: the public address of the configuration, else the one the browser used. */
    private String defaultServer() {
        if (StringUtils.isNotBlank(configuration.getPublicUrl())) {
            return StringUtils.removeEnd(configuration.getPublicUrl(), "/");
        }
        VaadinRequest request = VaadinRequest.getCurrent();
        String host = request == null ? null : request.getHeader("Host");
        return host == null ? "" : (request.isSecure() ? "https://" : "http://") + host + request.getContextPath();
    }

    private void update() {
        String server = StringUtils.removeEnd(StringUtils.strip(serverField.getValue()), "/");
        String url = link(server, offer);
        link.setValue(url);
        serverHelp.setText(reachable(server) ? "" : String.format(loc.getValue(J_L.HELP_PHONE_SERVER_LOCAL), hostOf(server)));
        try {
            qr.setSrc("data:image/png;base64," + Base64.getEncoder().encodeToString(png(url)));
        } catch (WriterException | java.io.IOException e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** {@code joi://pair?server=<address>&offer=<secret>}. */
    static String link(String server, String offer) {
        return SCHEME + "://pair?server=" + URLEncoder.encode(server, StandardCharsets.UTF_8) + "&offer=" + URLEncoder.encode(offer, StandardCharsets.UTF_8);
    }

    /** Whether a phone can reach the address: it is not this machine's own. */
    static boolean reachable(String server) {
        String host = hostOf(server);
        return !host.isEmpty() && !host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("[::1]") && !host.endsWith(".localhost");
    }

    private static String hostOf(String server) {
        try {
            String host = URI.create(server.contains("://") ? server : "http://" + server).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static byte[] png(String text) throws WriterException, java.io.IOException {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void startPolling() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        ui.setPollInterval(POLL_MILLIS);
        poll = ui.addPollListener(e -> check());
    }

    private void stopPolling() {
        UI ui = UI.getCurrent();
        if (poll != null) {
            poll.remove();
            poll = null;
            if (ui != null) {
                ui.setPollInterval(-1);
            }
        }
        deviceAuthorizationService.forgetOffer(offer);
    }

    /** Approves the device that came with the offer, as the user of this screen. */
    void check() {
        DeviceAuthorizationService.Request request = deviceAuthorizationService.waitingForOffer(offer);
        if (request == null) {
            return;
        }
        try {
            deviceAuthorizationService.approve(request.userCode());
            Notification.success(String.format(loc.getValue(J_L.MSG_PHONE_CONNECTED), request.clientName()));
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

    // for tests

    String getOffer() {
        return offer;
    }

    TextField getServerField() {
        return serverField;
    }

    TextField getLink() {
        return link;
    }

    Image getQr() {
        return qr;
    }
}
