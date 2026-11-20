package com.github.enerccio.joi.ui.tools;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.PageSourceType;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.SearchSourceType;
import com.github.enerccio.joi.ui.dialogs.AutoApprovedDialog;
import com.github.enerccio.joi.ui.dialogs.joi.JoiTasksPart;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools in the UI: the permissions of a place (stored as they change, with what is in force and where from), the web
 * options of the user (kept, never showing a secret), and the Tasks tab (approve, deny, cancel, and what is approved ahead
 * of time).
 */
class ToolsUiTest extends PlatformTestBase {

    @Autowired
    private ToolMetadataService metadataService;
    @Autowired
    private WebToolSettingsService settings;
    @Autowired
    private WebTaskService tasks;
    @Autowired
    private JoiService joiService;

    private UI ui;
    private User me;
    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        loginAdmin();
        for (ToolMetadata left : metadataService.findAll(ToolScope.APP, null)) {
            metadataService.delete(left, true);
        }
        me = login();
        joi = joiService.create("Mira");
    }

    @AfterEach
    void tearDown() throws Exception {
        UI.setCurrent(null);
        loginAdmin();
        for (ToolMetadata left : metadataService.findAll(ToolScope.APP, null)) {
            metadataService.delete(left, true);
        }
    }

    private static void change(com.vaadin.flow.component.select.Select<ToolPermission> select, ToolPermission value) {
        select.setValue(value);
        ComponentUtil.fireEvent(select, new AbstractField.ComponentValueChangeEvent<>(select, select, null, true));
    }

    private static void click(Button button) {
        ComponentUtil.fireEvent(button, new ClickEvent<>(button));
    }

    @Test
    void thePermissionsOfAPlaceAreStoredAndSayWhatIsInForce() throws Exception {
        ToolPermissionsPanel panel = new ToolPermissionsPanel(ToolScope.USER);
        panel.create();
        panel.load(null);
        // nothing set anywhere: the tool asks, built in
        assertThat(panel.getSelect(ServerTool.WEB_SEARCH).getValue()).isNull();
        assertThat(panel.getState(ServerTool.WEB_SEARCH)).contains("Ask").contains("built in");

        change(panel.getSelect(ServerTool.WEB_SEARCH), ToolPermission.ALLOW);
        assertThat(metadataService.find(ToolScope.USER, null, ServerTool.WEB_SEARCH).getPermission()).isEqualTo(ToolPermission.ALLOW);
        assertThat(panel.getState(ServerTool.WEB_SEARCH)).contains("Allow").contains("your settings");

        // the administrator's stricter value is a ceiling over it
        loginAdmin();
        ToolPermissionsPanel admin = new ToolPermissionsPanel(ToolScope.APP);
        admin.create();
        admin.load(null);
        change(admin.getSelect(ServerTool.WEB_SEARCH), ToolPermission.DENY);
        assertThat(admin.getState(ServerTool.WEB_SEARCH)).contains("Deny").contains("administrator");
        loginAs(me);
        panel.load(null);
        assertThat(panel.getState(ServerTool.WEB_SEARCH)).contains("Deny").contains("administrator").contains("stricter");

        // inherit again: nothing is kept of it
        change(panel.getSelect(ServerTool.WEB_SEARCH), null);
        assertThat(metadataService.find(ToolScope.USER, null, ServerTool.WEB_SEARCH).getPermission()).isNull();
    }

    @Test
    void theAssistantsOwnPermissionIsItsLink() throws Exception {
        ToolPermissionsPanel panel = new ToolPermissionsPanel(ToolScope.JOI);
        panel.create();
        panel.load(joi);
        change(panel.getSelect(ServerTool.FETCH_PAGE), ToolPermission.DENY);
        assertThat(metadataService.find(ToolScope.JOI, joi, ServerTool.FETCH_PAGE).getPermission()).isEqualTo(ToolPermission.DENY);
        assertThat(panel.getState(ServerTool.FETCH_PAGE)).contains("Deny").contains("assistant");
    }

    @Test
    void theUsersWebOptionsAreKeptWithoutShowingTheirSecrets() throws Exception {
        WebSearchPanel panel = new WebSearchPanel(ToolScope.USER);
        panel.create();
        assertThat(panel.getSources()).isEmpty();
        assertThat(settings.searchIn(ToolScope.USER, null).from()).isNull();

        panel.getSources().add(new SearchSource("home", "Home", SearchSourceType.SEARXNG, true, true, "http://127.0.0.1:8888", null, List.of("example.com"), null));
        panel.getProxy().setValue("http://proxy:3128");
        panel.getMaxSearches().setValue(3);
        panel.getPageType().setValue(PageSourceType.CRAWL4AI_PROXY);
        panel.getPageUrl().setValue("http://127.0.0.1:11235");
        panel.saveNow();

        WebToolSettingsService.Search search = settings.searchIn(ToolScope.USER, null);
        assertThat(search.from()).isEqualTo(ToolScope.USER);
        assertThat(search.config().sources()).extracting(SearchSource::id).containsExactly("home");
        assertThat(search.config().sources().getFirst().sites()).containsExactly("example.com");
        assertThat(search.config().proxy()).isEqualTo("http://proxy:3128");
        assertThat(search.config().maxSearches()).isEqualTo(3);
        assertThat(settings.pagesIn(ToolScope.USER, null).config().type()).isEqualTo(PageSourceType.CRAWL4AI_PROXY);

        // shown again from the store; and taken back, the administrator's are in use
        WebSearchPanel again = new WebSearchPanel(ToolScope.USER);
        again.create();
        assertThat(again.getSources()).extracting(SearchSource::name).containsExactly("Home");
        assertThat(again.getGrid().getDataProvider().fetch(new Query<>()).toList()).hasSize(1);
        settings.clearSearch(ToolScope.USER, null);
        settings.clearPages(ToolScope.USER, null);
        assertThat(settings.searchIn(ToolScope.USER, null).from()).isNull();
    }

    @Test
    void theTasksTabApprovesDeniesAndRemembersWhatIsApprovedAhead() throws Exception {
        JoiTasksPart part = new JoiTasksPart();
        part.create();
        part.load(joi);
        JoiTask first = tasks.submit(joi, ServerTool.FETCH_PAGE, "What is there?", "http://127.0.0.1:1/a", null);
        part.shown();
        assertThat(part.getGrid().getDataProvider().fetch(new Query<>()).toList()).extracting(JoiTask::getUuid).containsExactly(first.getUuid());
        assertThat(part.getApproveButton().isEnabled()).isFalse();

        part.getGrid().select(part.getGrid().getDataProvider().fetch(new Query<>()).toList().getFirst());
        assertThat(part.getApproveButton().isEnabled()).isTrue();
        assertThat(part.getApproveAllButton().isEnabled()).isTrue();
        assertThat(part.getDenyButton().isEnabled()).isTrue();
        assertThat(part.getCancelButton().isEnabled()).isTrue();

        click(part.getCancelButton());
        // the cancel asks first; the service does it too
        tasks.cancel(first);
        part.shown();
        assertThat(tasks.find(first.getUuid()).getStatus()).isEqualTo(JoiTaskStatus.CANCELLED);
        part.getGrid().select(part.getGrid().getDataProvider().fetch(new Query<>()).toList().getFirst());
        assertThat(part.getApproveButton().isEnabled()).isFalse();
        assertThat(part.getCancelButton().isEnabled()).isFalse();

        // what is approved ahead is listed and can be taken back
        tasks.autoApprove(joi, ServerTool.FETCH_PAGE, true);
        AutoApprovedDialog dialog = new AutoApprovedDialog(joi);
        dialog.create();
        assertThat(dialog.getGrid().getDataProvider().fetch(new Query<>()).toList()).containsExactly(ServerTool.FETCH_PAGE);
        tasks.autoApprove(joi, ServerTool.FETCH_PAGE, false);
        AutoApprovedDialog empty = new AutoApprovedDialog(joi);
        empty.create();
        assertThat(empty.getGrid().getDataProvider().fetch(new Query<>()).toList()).isEmpty();
    }
}
