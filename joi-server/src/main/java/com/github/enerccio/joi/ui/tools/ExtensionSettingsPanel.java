package com.github.enerccio.joi.ui.tools;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.extensions.ExtensionSettingsService;
import com.github.enerccio.joi.domain.extensions.ExtensionSettingsService.Place;
import com.github.enerccio.joi.domain.extensions.JoiExtensions;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.extensions.ExtensionSetting;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings the loaded extensions ask for ({@link ExtensionSettings}) in one place of the chain: the administrator's (Administration
 * → Tools) or the user's (User Settings → Tools). A section per extension: its fields (a secret is never shown again: its field says
 * whether one is saved, and an empty field keeps it), Save, and Remove, which takes the place's settings away (the user's then
 * inherit the administrator's). Shows the extensions that were loaded when the page was made; nothing when none asks for settings.
 */
@Configurable
public class ExtensionSettingsPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiExtensions extensions;

    @Autowired
    private ExtensionSettingsService settingsService;

    private final ToolScope scope;
    private VerticalLayout layout;
    /** The fields of each extension, by its symbolic name and the key of the setting. */
    private final Map<String, Map<String, TextFieldBase<?, String>>> fields = new LinkedHashMap<>();

    public ExtensionSettingsPanel(ToolScope scope) {
        this.scope = scope;
    }

    public Component create() {
        layout = new VerticalLayout();
        layout.setPadding(false);
        layout.addClassName(JoiCssStyles.EXTENSION_SETTINGS);
        for (Map.Entry<String, ExtensionSettings> entry : extensions.allSettings()) {
            layout.add(section(entry.getKey(), entry.getValue()));
        }
        layout.setVisible(!fields.isEmpty());
        refresh();
        return layout;
    }

    private Component section(String owner, ExtensionSettings settings) {
        VerticalLayout section = new VerticalLayout(new H4(settings.caption()));
        section.setPadding(false);
        if (settings.description() != null) {
            Span description = new Span(settings.description());
            description.addClassName(CssStyles.HINT_TEXT);
            section.add(description);
        }
        Span help = new Span(loc.getValue(scope == ToolScope.APP ? J_L.HELP_EXTENSION_SETTINGS_APP : J_L.HELP_EXTENSION_SETTINGS_USER));
        help.addClassName(CssStyles.HINT_TEXT);
        section.add(help);
        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));
        Map<String, TextFieldBase<?, String>> own = new LinkedHashMap<>();
        for (ExtensionSetting setting : settings.fields()) {
            TextFieldBase<?, String> field;
            if (setting.secret()) {
                PasswordField password = new PasswordField(setting.label());
                password.setAutocomplete(Autocomplete.OFF);
                field = password;
            } else {
                field = new TextField(setting.label());
            }
            field.setWidthFull();
            if (setting.description() != null) {
                field.setTooltipText(setting.description());
            }
            own.put(setting.key(), field);
            form.add(field);
        }
        fields.put(owner, own);
        Button save = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), _ -> save(owner));
        save.setThemeName("primary");
        Button remove = new Button(loc.getValue(J_L.LABEL_REMOVE_EXTENSION_SETTINGS), Solid.TRASH.create(),
                _ -> ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> clear(owner)));
        section.add(form, new HorizontalLayout(save, remove));
        return section;
    }

    /** Shows what is stored now. */
    public void refresh() {
        for (Map.Entry<String, Map<String, TextFieldBase<?, String>>> entry : fields.entrySet()) {
            try {
                ExtensionSettings settings = settingsService.declared(entry.getKey());
                Place place = settingsService.place(scope, null, entry.getKey());
                for (Map.Entry<String, TextFieldBase<?, String>> field : entry.getValue().entrySet()) {
                    ExtensionSetting setting = settings == null ? null : settings.field(field.getKey());
                    if (setting != null && setting.secret()) {
                        field.getValue().clear();
                        field.getValue().setHelperText(place.secrets().contains(field.getKey()) ? loc.getValue(J_L.HELP_WEB_PROXY_AUTH_SAVED) : null);
                    } else {
                        field.getValue().setValue(place.values().getOrDefault(field.getKey(), ""));
                    }
                }
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        }
    }

    /** Saves the extension's section (its Save button). */
    public void save(String owner) {
        try {
            ExtensionSettings settings = settingsService.declared(owner);
            if (settings == null) {
                Notification.error(loc.getValue(J_L.MSG_EXTENSION_NOT_LOADED));
                return;
            }
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> secrets = new LinkedHashMap<>();
            fields.get(owner).forEach((key, field) -> {
                ExtensionSetting setting = settings.field(key);
                if (setting == null) {
                    return;
                }
                if (setting.secret()) {
                    if (!field.isEmpty()) {
                        secrets.put(key, field.getValue());
                    }
                } else {
                    values.put(key, field.getValue());
                }
            });
            settingsService.save(scope, null, owner, values, secrets);
            refresh();
            Notification.success(loc.getValue(J_L.MSG_SETTINGS_SAVED));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void clear(String owner) {
        try {
            settingsService.clear(scope, null, owner);
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The field of a setting, for tests. */
    public TextFieldBase<?, String> getField(String owner, String key) {
        Map<String, TextFieldBase<?, String>> own = fields.get(owner);
        return own == null ? null : own.get(key);
    }
}
