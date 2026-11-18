package com.github.enerccio.joi.ui.workspace;

import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.components.TextModelTypes;
import com.github.enerccio.joi.ui.dialogs.JoiAIDialog;
import com.github.enerccio.joi.ui.workspace.parts.AIPartAccess;
import com.github.enerccio.marginalia.domain.collections.AIType;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.test.ComponentTree;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMScenario;
import com.github.enerccio.marginalia.ui.dialogs.AIDialog;
import com.google.gson.JsonObject;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace after the login: the Models part lists the user's AIs (not other users' private ones), the platform's
 * AI dialog saves a new one into Joi's database, and the selected tab is kept in the user setting.
 */
class JoiWorkspaceTest extends PlatformTestBase {

    @Autowired
    private Localization loc;

    @Autowired
    private SettingService settingService;

    /** The UI of the test, held strongly (the current UI is a weak reference). */
    private UI ui;

    @BeforeEach
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private JoiWorkspace openWorkspace() throws Exception {
        JoiWorkspace workspace = new JoiWorkspace();
        workspace.setOnLogout(() -> { });
        // built, not attached: the test UI has no VaadinSession
        workspace.create();
        workspace.refresh();
        workspace.restoreAutosaved();
        return workspace;
    }

    private static List<AI> models(JoiWorkspace workspace) {
        return AIPartAccess.rows(workspace.getPart("models"));
    }

    @Test
    void modelsPartListsOnlyTheUsersAIs() throws Exception {
        login();
        AI other = createAI();
        login();
        AI own = createAI();

        JoiWorkspace workspace = openWorkspace();

        // the first tab is the assistants
        assertThat(workspace.getActivePart()).isSameAs(workspace.getPart("jois"));
        assertThat(models(workspace)).extracting(AI::getUuid)
                .contains(own.getUuid())
                .doesNotContain(other.getUuid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiDialogSavesANewModel() throws Exception {
        login();
        JoiWorkspace workspace = openWorkspace();
        String name = uniqueName("dialog-ai");

        JoiAIDialog dialog = new JoiAIDialog();
        dialog.setOnSave(() -> {
            try {
                workspace.getPart("models").refresh();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        dialog.create();
        ((TextField) field(dialog, "nameField")).setValue(name);
        ((IntegerField) field(dialog, "maxContextField")).setValue(4096);
        ((IntegerField) field(dialog, "maxResponseField")).setValue(256);
        ((ComboBox<AIType>) field(dialog, "typeCombo")).setValue(AIType.OPEN_AI_COMPATIBLE);
        ((TextField) field(dialog, "urlField")).setValue(llm.baseUrl());
        ComboBox<String> modelCombo = field(dialog, "modelCombo");
        modelCombo.setItems(MockLLMScenario.DEFAULT_MODEL);
        modelCombo.setValue(MockLLMScenario.DEFAULT_MODEL);
        dialog.selectTypes(TextLLMType.CHAT, TextLLMType.AUXILIARY);
        ComponentTree.button(dialog, loc.getValue(L.LABEL_OK)).click();

        AI saved = models(workspace).stream().filter(ai -> name.equals(ai.getName())).findFirst().orElseThrow();
        assertThat(saved).isInstanceOf(OpenAICompatible.class);
        AI stored = aiService.findForUser(saved.getUuid());
        assertThat(((OpenAICompatible) stored).getUri()).isEqualTo(llm.baseUrl());
        assertThat(TextModelTypes.get(stored)).containsExactlyInAnyOrder(TextLLMType.CHAT, TextLLMType.AUXILIARY);
        // the grid shows the new types after the save, not the ones before it
        assertThat(TextModelTypes.get(saved)).containsExactlyInAnyOrder(TextLLMType.CHAT, TextLLMType.AUXILIARY);
    }

    @Test
    void editDialogShowsAndChangesTheTypes() throws Exception {
        login();
        AI ai = createAI();
        TextModelTypes.set(ai, List.of(TextLLMType.DEEP_REASONING));
        ai = aiService.save(ai);

        JoiAIDialog dialog = new JoiAIDialog(aiService.findForUser(ai.getUuid()));
        dialog.create();
        assertThat(dialog.getTypesBox().getValue()).containsExactly(TextLLMType.DEEP_REASONING);

        dialog.selectTypes(TextLLMType.CHAT);
        ComponentTree.button(dialog, loc.getValue(L.LABEL_OK)).click();

        AI stored = aiService.findForUser(ai.getUuid());
        assertThat(TextModelTypes.get(stored)).containsExactly(TextLLMType.CHAT);
        // the platform's fields are kept
        assertThat(((OpenAICompatible) stored).getUri()).isEqualTo(llm.baseUrl());
    }

    @Test
    void typesBoxIsInTheDialog() throws Exception {
        login();
        JoiAIDialog dialog = new JoiAIDialog();
        dialog.create();

        assertThat(ComponentTree.all(dialog)).contains(dialog.getTypesBox());
        assertThat(dialog.getTypesBox().getValue()).isEmpty();
    }

    @Test
    void selectedTabIsKeptInTheUserSetting() throws Exception {
        login();
        JoiWorkspace workspace = openWorkspace();

        JsonObject state = workspace.storeState();
        assertThat(state.get("tab").getAsString()).isEqualTo("jois");

        // the autosave stores it once the old state was restored (an empty one for a new user)
        workspace.autosave();
        assertThat(settingService.getUserSetting().getWorkspaceState().get("tab").getAsString()).isEqualTo("jois");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(AIDialog dialog, String name) {
        try {
            Field field = AIDialog.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(dialog);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
