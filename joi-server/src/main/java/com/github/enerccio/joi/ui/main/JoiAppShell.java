package com.github.enerccio.joi.ui.main;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.BodySize;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.ColorScheme.Value;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * The page around every view: server push (replies and events arrive from other threads), Lumo in the dark scheme,
 * Joi's stylesheet (which imports the platform's).
 */
@Push
@StyleSheet(Lumo.STYLESHEET)
@ColorScheme(Value.DARK)
@BodySize(height = "100vh", width = "100vw")
@CssImport(value = "./styles/joi-styles.css")
@Configurable(preConstruction = true)
public class JoiAppShell implements AppShellConfigurator {

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.setPageTitle("Joi");
    }
}
