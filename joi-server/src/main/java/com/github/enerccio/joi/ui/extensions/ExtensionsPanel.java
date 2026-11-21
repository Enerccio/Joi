package com.github.enerccio.joi.ui.extensions;

import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.domain.service.ExtensionVerificationException;
import com.github.enerccio.marginalia.domain.service.OsgiService;
import com.github.enerccio.marginalia.domain.service.OsgiService.ExtensionReport;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.JavascriptLibrary;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.dialogs.ExtensionLogDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.io.IOException;

/**
 * Administration → Extensions, after Marginalia's tab: the extensions of the installation (the bundles of the platform's {@link OsgiService}),
 * loaded from a JAR (also dropped into the {@code extensions} folder of the data folder), stopped and started without removing them, with the
 * log each one writes, the hash of its file and the report of the check that it fits this version. The service is the platform's; the
 * panel is not shown at all when it is not running (tests).
 */
@Configurable
public class ExtensionsPanel implements LLMInteract {

    private static final Logger log = LoggerFactory.getLogger(ExtensionsPanel.class);

    @Autowired
    private Localization loc;

    @Autowired(required = false)
    private OsgiService osgiService;

    private Grid<Bundle> grid;

    public Component create() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        Span hint = new Span(loc.getValue(J_L.MSG_EXTENSIONS_HINT));
        hint.addClassName(CssStyles.TEXT_SECONDARY);

        InMemoryUploadHandler handler = new InMemoryUploadHandler((metadata, data) -> {
            try {
                osgiService.installPackage(metadata.fileName(), data);
                Notification.show(loc.getValue(J_L.MSG_EXTENSION_INSTALLED));
            } catch (ExtensionVerificationException e) {
                log.error("Extension {} was rejected:\n{}", e.getExtension(), e.getReport());
                Notification.show(String.format(loc.getValue(J_L.ERROR_EXTENSION_INCOMPATIBLE), e.getExtension()), 5000, Notification.Position.MIDDLE);
                showReport(e.getReport());
            } catch (Exception e) {
                log.error("Failed to load extension bundle", e);
                Notification.show(String.format(loc.getValue(J_L.ERROR_EXTENSION_INSTALL_FAILED), e.getMessage()), 5000, Notification.Position.MIDDLE);
            }
            refresh();
        });
        Upload upload = new Upload(handler);
        upload.setAcceptedFileExtensions(".jar");
        upload.setAcceptedMimeTypes("application/zip", "application/jar", "application/java-archive", "application/x-java-archive");
        upload.setUploadButton(new Button(loc.getValue(J_L.LABEL_UPLOAD_EXTENSION), VaadinIcon.UPLOAD.create()));
        upload.setDropLabel(new Div(new Button(loc.getValue(J_L.LABEL_UPLOAD_EXTENSION))));
        Button refresh = new Button(loc.getValue(L.LABEL_REFRESH), VaadinIcon.REFRESH.create(), _ -> refresh());
        HorizontalLayout toolbar = new HorizontalLayout(upload, refresh);
        toolbar.setWidthFull();
        toolbar.setAlignItems(HorizontalLayout.Alignment.CENTER);

        grid = new Grid<>();
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.addColumn(Bundle::getBundleId).setHeader(loc.getValue(J_L.LABEL_BUNDLE_ID)).setFlexGrow(0).setWidth("80px");
        grid.addColumn(ExtensionsPanel::extensionName).setHeader(loc.getValue(J_L.LABEL_BUNDLE_NAME)).setFlexGrow(1);
        grid.addComponentColumn(this::version).setHeader(loc.getValue(J_L.LABEL_BUNDLE_VERSION)).setFlexGrow(0).setWidth("150px");
        grid.addColumn(bundle -> osgiService.isStopped(bundle) ? loc.getValue(J_L.ENUM_BUNDLE_STATE_STOPPED) : state(bundle.getState()))
                .setHeader(loc.getValue(J_L.LABEL_BUNDLE_STATE)).setFlexGrow(0).setWidth("140px");
        grid.addComponentColumn(this::verification).setHeader(loc.getValue(J_L.LABEL_BUNDLE_VERIFICATION)).setFlexGrow(0).setWidth("140px");
        grid.addComponentColumn(this::actions).setHeader(loc.getValue(J_L.LABEL_ACTIONS)).setFlexGrow(0).setWidth("400px");
        layout.add(hint, toolbar, grid);
        refresh();
        return layout;
    }

    /** Reads the extensions again. */
    public void refresh() {
        if (grid != null && osgiService != null) {
            grid.setItems(osgiService.getBundles());
        }
    }

    static String extensionName(Bundle bundle) {
        String name = bundle.getHeaders().get("Bundle-Name");
        return name != null && !name.isBlank() ? name : bundle.getSymbolicName();
    }

    private Component version(Bundle bundle) {
        HorizontalLayout version = new HorizontalLayout(new Span(bundle.getVersion().toString()));
        version.setAlignItems(HorizontalLayout.Alignment.CENTER);
        version.setSpacing(false);
        version.addClassName(CssStyles.GAP_S);
        try {
            String sha256 = osgiService.getSha256(bundle);
            Button hash = new Button("#");
            hash.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            UIUtils.addTooltip(hash, loc.getValue(J_L.LABEL_SHA256) + ": " + sha256);
            hash.addClickListener(e -> {
                JavascriptLibrary.copyToClipboard(e.getSource().getUI().orElseThrow().getPage(), sha256);
                Notification.show(loc.getValue(J_L.MSG_COPIED_HASH));
            });
            version.add(hash);
        } catch (IOException e) {
            log.warn("Failed to compute SHA-256 of extension {}", extensionName(bundle), e);
        }
        return version;
    }

    private Component verification(Bundle bundle) {
        ExtensionReport report = osgiService.getVerificationReport(bundle);
        if (report == null) {
            return new Div();
        }
        Button button = new Button(loc.getValue(report.valid() ? J_L.LABEL_VALID : J_L.LABEL_INVALID));
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        if (!report.valid()) {
            button.addThemeVariants(ButtonVariant.LUMO_ERROR);
        }
        button.addClickListener(_ -> showReport(report.text()));
        return button;
    }

    private Component actions(Bundle bundle) {
        boolean stopped = osgiService.isStopped(bundle);
        Button toggle = stopped ? new Button(loc.getValue(J_L.LABEL_START), VaadinIcon.PLAY.create())
                : new Button(loc.getValue(L.LABEL_STOP), VaadinIcon.STOP.create());
        toggle.addThemeVariants(ButtonVariant.LUMO_SMALL);
        toggle.addClickListener(_ -> toggle(bundle, stopped));
        Button logButton = new Button(loc.getValue(L.LABEL_LOG), VaadinIcon.FILE_TEXT_O.create());
        logButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        logButton.addClickListener(_ -> ExtensionLogDialog.show(bundle, extensionName(bundle)));
        Button unload = new Button(loc.getValue(J_L.LABEL_UNLOAD_EXTENSION), VaadinIcon.TRASH.create());
        unload.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        unload.addClickListener(_ -> unload(bundle));
        HorizontalLayout actions = new HorizontalLayout(toggle, logButton, unload);
        actions.addClassName(CssStyles.FLEX_WRAP);
        return actions;
    }

    private void toggle(Bundle bundle, boolean stopped) {
        try {
            if (stopped) {
                osgiService.startExtension(bundle);
                Notification.show(loc.getValue(J_L.MSG_EXTENSION_STARTED));
            } else {
                osgiService.stopExtension(bundle);
                Notification.show(loc.getValue(J_L.MSG_EXTENSION_STOPPED));
            }
        } catch (ExtensionVerificationException e) {
            log.error("Extension {} was rejected:\n{}", e.getExtension(), e.getReport());
            Notification.show(String.format(loc.getValue(J_L.ERROR_EXTENSION_INCOMPATIBLE), e.getExtension()), 5000, Notification.Position.MIDDLE);
            showReport(e.getReport());
        } catch (Exception e) {
            log.error("Failed to {} extension", stopped ? "start" : "stop", e);
            Notification.show(String.format(loc.getValue(stopped ? J_L.ERROR_EXTENSION_START_FAILED : J_L.ERROR_EXTENSION_STOP_FAILED), e.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
        refresh();
    }

    private void unload(Bundle bundle) {
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_UNLOAD_EXTENSION), extensionName(bundle)), () -> {
            try {
                osgiService.uninstallPackage(bundle);
                Notification.show(loc.getValue(J_L.MSG_EXTENSION_UNINSTALLED));
            } catch (Exception e) {
                log.error("Failed to unload extension", e);
                Notification.show(String.format(loc.getValue(J_L.ERROR_EXTENSION_UNINSTALL_FAILED), e.getMessage()), 5000, Notification.Position.MIDDLE);
            }
            refresh();
        });
    }

    private void showReport(String report) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(loc.getValue(J_L.LABEL_VERIFICATION_REPORT));
        dialog.setWidth("900px");
        UIUtils.fitPhone(dialog, true);
        TextArea text = new TextArea();
        text.setValue(report);
        text.setReadOnly(true);
        text.setWidthFull();
        text.setHeight("400px");
        text.addClassName(CssStyles.TEXT_MONOSPACE);
        dialog.add(text);
        dialog.getFooter().add(new Button(loc.getValue(L.LABEL_CLOSE), _ -> dialog.close()));
        dialog.open();
    }

    private String state(int state) {
        return switch (state) {
            case Bundle.UNINSTALLED -> loc.getValue(J_L.ENUM_BUNDLE_STATE_UNINSTALLED);
            case Bundle.INSTALLED -> loc.getValue(J_L.ENUM_BUNDLE_STATE_INSTALLED);
            case Bundle.RESOLVED -> loc.getValue(J_L.ENUM_BUNDLE_STATE_RESOLVED);
            case Bundle.STARTING -> loc.getValue(J_L.ENUM_BUNDLE_STATE_STARTING);
            case Bundle.STOPPING -> loc.getValue(J_L.ENUM_BUNDLE_STATE_STOPPING);
            case Bundle.ACTIVE -> loc.getValue(J_L.ENUM_BUNDLE_STATE_ACTIVE);
            default -> loc.getValue(J_L.ENUM_BUNDLE_STATE_UNKNOWN).formatted(state);
        };
    }
}
