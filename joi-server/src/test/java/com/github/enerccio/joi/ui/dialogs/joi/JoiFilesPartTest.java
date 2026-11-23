package com.github.enerccio.joi.ui.dialogs.joi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** The Files tab: her workspace is listed once the dialog has her, never before (that was an internal error on every open). */
class JoiFilesPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;

    private UI ui;
    private Logger errors;
    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        // an internal error the user gets the error dialog for is logged here
        errors = (Logger) LoggerFactory.getLogger(UIUtils.class);
        logged = new ListAppender<>();
        logged.start();
        errors.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        errors.detachAppender(logged);
        UI.setCurrent(null);
        ui = null;
    }

    @Test
    void theDialogOpensWithoutAnError() throws Exception {
        Joi joi = joiService.create("Mira");
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        // the tab shown lists the files again
        dialog.getFilesPart().shown();

        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).isEmpty();
    }
}
