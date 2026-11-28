package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.persona.TemplateOverride;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.ui.dialogs.joi.JoiTemplatesPart;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.joi.ui.dialogs.TemplateCompareDialog;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The templates of both Templates tabs against their parent in the chain: which text is the parent, the red (i) when a set
 * text differs from it, and the compare dialog writing its text back into the field.
 */
class TemplateCompareTest extends PlatformTestBase {

    @Autowired
    private PersonaService personaService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private JoiService joiService;

    private UI ui;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    @Test
    void theParentOfTheUserIsTheDefaultAndOfAnAssistantTheUserElseTheDefault() throws Exception {
        JoiDefaults defaults = personaService.defaults();
        JoiUserSetting setting = personaService.getUserSetting();
        JoiPromptField field = JoiPromptField.FACTS_PROMPT;
        assertThat(TemplateOverride.parentOfUser(field, defaults)).isEqualTo(defaults.factsPrompt());
        assertThat(TemplateOverride.parentOfAssistant(field, setting, defaults)).isEqualTo(defaults.factsPrompt());
        field.setOfUser(setting, "The user's facts of {{conversation}}");
        assertThat(TemplateOverride.parentOfUser(field, defaults)).isEqualTo(defaults.factsPrompt());
        assertThat(TemplateOverride.parentOfAssistant(field, setting, defaults)).isEqualTo("The user's facts of {{conversation}}");
    }

    @Test
    void theUserSettingsAreMarkedAgainstTheDefaultAndTheCompareDialogWritesBack() throws Exception {
        UserSettingsPart part = new UserSettingsPart();
        part.create();
        part.refresh();
        TextAreaPopoverComponent area = part.getPromptArea(JoiPromptField.FACTS_PROMPT);
        String builtIn = personaService.defaults().factsPrompt();
        // not set: nothing to mark
        assertThat(PromptAreas.isOverridden(area)).isFalse();
        area.setValue(builtIn);
        assertThat(PromptAreas.isOverridden(area)).isFalse();
        area.setValue("Facts of {{conversation}}");
        assertThat(PromptAreas.isOverridden(area)).isTrue();

        TemplateCompareDialog dialog = PromptAreas.compare(area);
        assertThat(dialog).isNotNull();
        assertThat(dialog.getEdit().getValue()).isEqualTo("Facts of {{conversation}}");
        dialog.getEdit().setValue(builtIn + "\nAnd one more line.");
        dialog.clickSave();
        assertThat(area.getValue()).isEqualTo(builtIn + "\nAnd one more line.");
        assertThat(PromptAreas.isOverridden(area)).isTrue();

        // Cancel leaves the field; taking the inherited text clears the mark
        TemplateCompareDialog again = PromptAreas.compare(area);
        again.getEdit().setValue("thrown away");
        again.close();
        assertThat(area.getValue()).isEqualTo(builtIn + "\nAnd one more line.");
        again = PromptAreas.compare(area);
        again.getEdit().setValue(builtIn);
        again.clickSave();
        assertThat(PromptAreas.isOverridden(area)).isFalse();

        // the form's Save stores what the dialog wrote
        area.setValue("Facts of {{conversation}}, briefly");
        part.clickSave();
        assertThat(personaService.getUserSetting().getFactsPrompt()).isEqualTo("Facts of {{conversation}}, briefly");
    }

    @Test
    void anAssistantsTemplateIsMarkedAgainstTheUsersElseTheDefaultAndStoredFromTheDialog() throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        JoiPromptField.FACTS_PROMPT.setOfUser(setting, "The user's facts of {{conversation}}");
        settingService.save(setting);
        Joi joi = joiService.create("Mira");
        joi = joiService.update(joi, j -> {
            JoiPromptField.FACTS_PROMPT.setOf(j, "The user's facts of {{conversation}}");
            JoiPromptField.SUMMARY_PROMPT.setOf(j, "Her summary of {{conversation}}");
        });

        JoiTemplatesPart part = templates(joi);
        // the same as the user's: not marked, though it differs from the default
        assertThat(PromptAreas.isOverridden(part.getArea(JoiPromptField.FACTS_PROMPT))).isFalse();
        // the user sets no summary prompt: hers is compared with the default
        assertThat(PromptAreas.isOverridden(part.getArea(JoiPromptField.SUMMARY_PROMPT))).isTrue();
        // not set at all
        assertThat(PromptAreas.isOverridden(part.getArea(JoiPromptField.BODY_PROMPT))).isFalse();

        TemplateCompareDialog dialog = PromptAreas.compare(part.getArea(JoiPromptField.FACTS_PROMPT));
        dialog.getEdit().setValue("Her facts of {{conversation}}");
        dialog.clickSave();
        assertThat(PromptAreas.isOverridden(part.getArea(JoiPromptField.FACTS_PROMPT))).isTrue();
        // the tab stores a change at once
        assertThat(JoiPromptField.FACTS_PROMPT.of(joiService.find(joi.getId()))).isEqualTo("Her facts of {{conversation}}");
        assertThat(PromptAreas.isOverridden(templates(joi).getArea(JoiPromptField.FACTS_PROMPT))).isTrue();
    }

    private JoiTemplatesPart templates(Joi joi) throws Exception {
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        return dialog.getTemplatesPart();
    }
}
