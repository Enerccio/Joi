package com.github.enerccio.joi.domain.workspace;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiWorkspaceService;
import com.github.enerccio.joi.domain.settings.JoiAppSettings;
import com.github.enerccio.joi.ui.settings.AppSettingsPanel;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.domain.service.agent.VirtualFileSystem;
import com.github.enerccio.marginalia.domain.service.agent.VirtualPath;
import com.github.enerccio.marginalia.domain.service.agent.WorkspaceLimits;
import com.github.enerccio.marginalia.domain.service.function.FunctionDefinition;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The workspace of an assistant: her own files, the administrator's limits, the file functions of her turns and her tasks. */
class AssistantWorkspaceTest extends PlatformTestBase {

    @Autowired
    private JoiWorkspaceService workspaceService;
    @Autowired
    private JoiAppSettingsService appSettings;
    @Autowired
    private JoiService joiService;

    private Joi mira;
    private UI ui;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        loginAdmin();
        appSettings.save(JoiAppSettings.DEFAULT);
        mira = joiService.create("Mira");
    }

    @AfterEach
    void tearDown() throws Exception {
        loginAdmin();
        appSettings.save(JoiAppSettings.DEFAULT);
        UI.setCurrent(null);
        ui = null;
    }

    private static void write(VirtualFileSystem files, String path, String text) {
        files.writeText(files.parse(path), text, false);
    }

    private static String call(FunctionSet tools, String function, String arguments) throws Exception {
        return tools.call(function, arguments, Runnable::run, () -> false).get(10, TimeUnit.SECONDS);
    }

    @Test
    void eachAssistantHasAWorkspaceOfHerOwn() throws Exception {
        Joi noor = joiService.create("Noor");
        write(workspaceService.open(mira), "/notes.md", "Mira's notes");

        VirtualFileSystem hers = workspaceService.open(mira);
        assertThat(hers.readText(hers.parse("/notes.md"))).isEqualTo("Mira's notes");
        assertThat(workspaceService.open(noor).list(VirtualPath.ROOT)).isEmpty();
    }

    @Test
    void theFileFunctionsAreTheAgentsWithoutTheOnesForCommandResults() throws Exception {
        List<String> names = workspaceService.fileTools(mira).getDefinitions().stream().map(FunctionDefinition::getName).toList();
        assertThat(names).contains("list_files", "read_file", "write_file", "append_file", "replace_in_file", "grep_files", "move_file",
                "copy_file", "unzip_file", "merge_files", "delete_file").doesNotContain("list_command_results", "save_command_result");
    }

    @Test
    void theFunctionsWorkOnHerWorkspace() throws Exception {
        FunctionSet tools = workspaceService.fileTools(mira);
        call(tools, "write_file", "{\"path\":\"/plans/trip.md\",\"content\":\"Go to the sea.\"}");
        assertThat(call(tools, "read_file", "{\"path\":\"/plans/trip.md\"}")).contains("Go to the sea.");
        assertThat(call(tools, "list_files", "{\"recursive\":true}")).contains("trip.md");
        VirtualFileSystem files = workspaceService.open(mira);
        assertThat(files.readText(files.parse("/plans/trip.md"))).isEqualTo("Go to the sea.");
        call(tools, "delete_file", "{\"path\":\"/plans\",\"recursive\":true}");
        assertThat(workspaceService.open(mira).list(VirtualPath.ROOT)).isEmpty();
    }

    @Test
    void theLimitsAreTheAdministratorsAndHoldForTheFunctions() throws Exception {
        assertThat(workspaceService.limits()).isEqualTo(WorkspaceLimits.DEFAULT);
        appSettings.save(new JoiAppSettings(false).withWorkspaceLimits(new WorkspaceLimits(16, 64, 2)));
        assertThat(workspaceService.limits().maxFileBytes()).isEqualTo(16);

        FunctionSet tools = workspaceService.fileTools(mira);
        call(tools, "write_file", "{\"path\":\"/small.txt\",\"content\":\"tiny\"}");
        // a file over the limit, and a third file over the number, are refused with a reason
        assertThatThrownBy(() -> call(tools, "write_file", "{\"path\":\"/big.txt\",\"content\":\"" + "x".repeat(40) + "\"}"))
                .hasStackTraceContaining("limit of a file");
        call(tools, "write_file", "{\"path\":\"/second.txt\",\"content\":\"tiny\"}");
        assertThatThrownBy(() -> call(tools, "write_file", "{\"path\":\"/third.txt\",\"content\":\"tiny\"}")).isNotNull();
        assertThat(workspaceService.open(mira).list(VirtualPath.ROOT)).hasSize(2);
    }

    @Test
    void whatTheAdministratorLeftOutIsTheDefault() {
        assertThat(JoiAppSettings.DEFAULT.workspaceLimits()).isEqualTo(WorkspaceLimits.DEFAULT);
        WorkspaceLimits partly = new JoiAppSettings(false, 0, 0, 7, 0, 0).workspaceLimits();
        assertThat(partly.maxFiles()).isEqualTo(7);
        assertThat(partly.maxFileBytes()).isEqualTo(WorkspaceLimits.DEFAULT.maxFileBytes());
    }

    @Test
    void anotherUsersAssistantHasNoWorkspaceForMe() throws Exception {
        Joi mine = mira;
        loginAs(createUser());
        assertThatThrownBy(() -> workspaceService.open(mine)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAdministratorSetsTheLimitsInTheAppSettings() throws Exception {
        AppSettingsPanel panel = new AppSettingsPanel();
        panel.create();
        assertThat(panel.getMaxFiles().getValue()).isEqualTo(WorkspaceLimits.DEFAULT.maxFiles());
        panel.getMaxFiles().setValue(12);
        panel.getMaxFileKb().setValue(8);
        panel.save();

        WorkspaceLimits limits = workspaceService.limits();
        assertThat(limits.maxFiles()).isEqualTo(12);
        assertThat(limits.maxFileBytes()).isEqualTo(8 * 1024);
        assertThat(limits.maxTotalBytes()).isEqualTo(WorkspaceLimits.DEFAULT.maxTotalBytes());
    }
}
