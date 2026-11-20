package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.*;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.domain.templates.SummaryTemplateData;
import com.github.enerccio.joi.ui.dialogs.MoodDialog;
import com.github.enerccio.joi.ui.dialogs.PersonalityDialog;
import com.github.enerccio.joi.ui.dialogs.SystemPromptPreviewDialog;
import com.github.enerccio.joi.ui.workspace.JoiWorkspace;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.service.TemplateService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults and their chain (the user's setting, else {@link JoiDefaults}), the personalities and moods, the rendered
 * system prompt, and the User Settings page with its dialogs.
 */
class PersonaAndSettingsTest extends PlatformTestBase {

    @Autowired
    private PersonaService personaService;

    @Autowired
    private SettingService settingService;

    @Autowired
    private TemplateService templateService;

    /** The UI of the test, held strongly (the current UI is a weak reference). */
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

    private JoiUserSetting setting() throws Exception {
        return personaService.getUserSetting();
    }

    @Test
    void everyBuiltInTemplateIsValidAndKnowsAllItsNames() throws Exception {
        JoiDefaults defaults = personaService.defaults();
        for (JoiPromptField field : JoiPromptField.values()) {
            TemplateService.ValidationResult result = templateService.isValidTemplate(field.fallback(defaults), field.templateName(),
                    field.dataClass());
            assertThat(result.isValid()).as(field + ": " + result.errorMessage()).isTrue();
            assertThat(result.unknownNames()).as(field.name()).isEmpty();
        }
        for (var personality : defaults.personalities()) {
            assertThat(templateService.isValidTemplate(personality.prompt(), "personality", ChatTemplateData.class).unknownNames())
                    .as(personality.key()).isEmpty();
        }
        assertThat(defaults.personalities()).extracting("key")
                .containsExactly("neutral", "warm", "cheerful", "sarcastic", "kuudere", "dandere", "tsundere", "yandere");
        assertThat(defaults.moods()).extracting("key").contains(JoiDefaults.DEFAULT_MOOD, "happy", "grumpy", "sleepy");
    }

    @Test
    void aPromptResolvesFromTheAssistantTheUserOrTheDefault() throws Exception {
        JoiDefaults defaults = personaService.defaults();
        assertThat(personaService.resolve(JoiPromptField.SUMMARY_PROMPT)).isEqualTo(defaults.summaryPrompt());

        JoiUserSetting setting = setting();
        JoiPromptField.SUMMARY_PROMPT.setOfUser(setting, "Mine: {{conversation}}");
        settingService.save(setting);
        assertThat(personaService.resolve(JoiPromptField.SUMMARY_PROMPT)).isEqualTo("Mine: {{conversation}}");
        assertThat(JoiPromptField.SUMMARY_PROMPT.resolve("The assistant's", setting(), defaults)).isEqualTo("The assistant's");

        setting = setting();
        JoiPromptField.SUMMARY_PROMPT.setOfUser(setting, "   ");
        settingService.save(setting);
        assertThat(personaService.resolve(JoiPromptField.SUMMARY_PROMPT)).isEqualTo(defaults.summaryPrompt());

        SummaryTemplateData data = new SummaryTemplateData();
        data.setConversation("USER: hi");
        assertThat(personaService.render(JoiPromptField.SUMMARY_PROMPT, data)).contains("USER: hi").contains("Joi").doesNotContain("{{");
    }

    @Test
    void personalitiesAreTheBuiltInOnesWithTheUsersChangesAndOwnOnes() throws Exception {
        PersonalitySetting tsundere = new PersonalitySetting("tsundere");
        tsundere.setPrompt("A softer tsundere.");
        personaService.savePersonality(tsundere);
        PersonalitySetting own = new PersonalitySetting("gremlin");
        own.setName("Gremlin");
        own.setPrompt("Chaotic but helpful.");
        own.setMoodExpressions(java.util.Map.of("happy", "Cackling happily."));
        personaService.savePersonality(own);

        List<Personality> all = personaService.personalities();
        assertThat(all).extracting(Personality::key).endsWith("yandere", "gremlin");
        Personality changed = personaService.personality("tsundere");
        assertThat(changed.prompt()).isEqualTo("A softer tsundere.");
        assertThat(changed.name()).isEqualTo("Tsundere");
        assertThat(changed.changed()).isTrue();
        assertThat(changed.moodExpressions()).containsKey("happy");
        assertThat(personaService.personality("gremlin").builtIn()).isFalse();

        // how a mood sounds depends on the personality
        Mood happy = personaService.mood("happy");
        assertThat(personaService.moodText(changed, happy)).contains("hide it");
        assertThat(personaService.moodText(personaService.personality("warm"), happy)).isEqualTo(happy.prompt());

        personaService.resetPersonality("tsundere");
        personaService.resetPersonality("gremlin");
        assertThat(personaService.personality("tsundere").changed()).isFalse();
        assertThat(personaService.personality("gremlin")).isNull();
    }

    @Test
    void aPersonalityIsClonedAsItIsNowIntoTheUsersOwnOne() throws Exception {
        PersonalitySetting tsundere = new PersonalitySetting("tsundere");
        tsundere.setPartConsent("If {{user}} begs, consider allowing it.");
        personaService.savePersonality(tsundere);
        // nobody decides on the parts unless the personality says how
        assertThat(personaService.personality("warm").decidesOnParts()).isFalse();
        assertThat(personaService.personality("tsundere").decidesOnParts()).isTrue();

        String key = personaService.clonePersonality("tsundere", "Tsundere (copy)");
        assertThat(key).isEqualTo("tsundere-copy");
        Personality copy = personaService.personality(key);
        Personality source = personaService.personality("tsundere");
        assertThat(copy.builtIn()).isFalse();
        assertThat(copy.name()).isEqualTo("Tsundere (copy)");
        assertThat(copy.prompt()).isEqualTo(source.prompt());
        assertThat(copy.partConsent()).isEqualTo("If {{user}} begs, consider allowing it.");
        assertThat(copy.moodExpressions()).isEqualTo(source.moodExpressions());

        // the keys stay unique, a copy of a copy too
        assertThat(personaService.clonePersonality("tsundere", null)).isEqualTo("tsundere-copy-2");
        assertThat(personaService.clonePersonality("tsundere-copy", null)).isEqualTo("tsundere-copy-3");
        assertThat(personaService.personality("tsundere-copy-2").name()).isEqualTo("Tsundere");
        // the copy is its own: changing the built-in one leaves it
        personaService.resetPersonality("tsundere");
        assertThat(personaService.personality(key).partConsent()).isEqualTo("If {{user}} begs, consider allowing it.");
        assertThat(personaService.personality("tsundere").decidesOnParts()).isFalse();
    }

    @Test
    void theDialogStoresHowSheDecidesOnHerParts() throws Exception {
        PersonalityDialog dialog = new PersonalityDialog("dandere");
        dialog.create();
        assertThat(dialog.getPartConsentArea().getValue()).isEmpty();
        dialog.getPartConsentArea().setValue("You are shy: refuse when it is sudden.");
        dialog.clickSave();
        assertThat(personaService.personality("dandere").partConsent()).isEqualTo("You are shy: refuse when it is sudden.");
        assertThat(personaService.personality("dandere").changed()).isTrue();
    }

    @Test
    void theDailyRollFollowsTheWeights() throws Exception {
        for (Mood mood : personaService.moods()) {
            MoodSetting setting = new MoodSetting(mood.key());
            setting.setWeight(mood.key().equals("grumpy") ? 5 : 0);
            personaService.saveMood(setting);
        }
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            assertThat(personaService.rollMood(random).key()).isEqualTo("grumpy");
        }
        MoodSetting none = new MoodSetting("grumpy");
        none.setWeight(0);
        personaService.saveMood(none);
        assertThat(personaService.rollMood(random).key()).isEqualTo(JoiDefaults.DEFAULT_MOOD);
    }

    @Test
    void theSystemPromptHasTheNamePersonalityMoodAndTheMemoriesSection() throws Exception {
        JoiUserSetting setting = setting();
        setting.setAssistantName("Kairo");
        setting.setPersonality("tsundere");
        settingService.save(setting);

        ChatTemplateData data = new ChatTemplateData();
        data.setMemories("- The user's cat is called Miso.");
        String prompt = personaService.renderChatSystemPrompt("grumpy", data);
        assertThat(prompt).contains("You are Kairo").contains("tsundere").contains("hmph")
                .contains("WHAT YOU REMEMBER").contains("Miso").doesNotContain("{{").doesNotContain("ABOUT THE USER");

        String withoutMemories = personaService.renderChatSystemPrompt("calm", new ChatTemplateData());
        assertThat(withoutMemories).doesNotContain("WHAT YOU REMEMBER").doesNotContain("\n\n\n");
        // a section is set off by one blank line, not two (the line of a standalone section tag stays)
        assertThat(prompt).doesNotContain("\n\n\n");
    }

    @Test
    void theSettingsPageStoresTheUsersLinkAndRefusesABrokenTemplate() throws Exception {
        UserSettingsPart part = new UserSettingsPart();
        part.create();
        part.refresh();
        assertThat(part.getPromptArea(JoiPromptField.MASTER_TEMPLATE).getValue()).isEmpty();
        assertThat(part.getPromptArea(JoiPromptField.MASTER_TEMPLATE).getPlaceholder())
                .isEqualTo(personaService.defaults().masterTemplate());
        assertThat(part.getPersonalitySelect().getValue().key()).isEqualTo(JoiDefaults.DEFAULT_PERSONALITY);
        assertThat(part.getPersonalityGrid().getDataProvider().fetch(new Query<>()).count()).isEqualTo(8);
        assertThat(part.getMoodGrid().getDataProvider().fetch(new Query<>()).count()).isEqualTo(personaService.defaults().moods().size());

        part.getAssistantNameField().setValue("Kairo");
        part.getPersonalitySelect().setValue(personaService.personality("kuudere"));
        part.getPromptArea(JoiPromptField.FACTS_PROMPT).setValue("Facts of {{conversation}}");
        part.clickSave();
        assertThat(setting().getAssistantName()).isEqualTo("Kairo");
        assertThat(setting().getPersonality()).isEqualTo("kuudere");
        assertThat(setting().getFactsPrompt()).isEqualTo("Facts of {{conversation}}");
        assertThat(setting().getMasterTemplate()).isNull();

        part.getPromptArea(JoiPromptField.MASTER_TEMPLATE).setValue("{{#memories}} never closed");
        part.clickSave();
        assertThat(setting().getMasterTemplate()).isNull();
    }

    @Test
    void theUsersNameIsTheSettingElseTheAccount() throws Exception {
        assertThat(personaService.userName()).isEqualTo(personaService.accountName()).isNotBlank();
        JoiUserSetting setting = setting();
        setting.setUserName("Pete");
        settingService.save(setting);
        assertThat(personaService.userName()).isEqualTo("Pete");
        assertThat(personaService.render(JoiPromptField.SUMMARY_PROMPT, new SummaryTemplateData())).contains("Pete");
    }

    @Test
    void unsavedChangesFreezeTheWorkspaceUntilSavedOrDiscarded() throws Exception {
        JoiWorkspace workspace = new JoiWorkspace();
        workspace.create();
        workspace.refresh();
        UserSettingsPart part = (UserSettingsPart) workspace.getPart("settings");
        assertThat(part.isChanged()).isFalse();
        assertThat(workspace.isNavigationLocked()).isFalse();

        part.getUserNameField().setValue("Pete");
        assertThat(part.isChanged()).isTrue();
        assertThat(workspace.isNavigationLocked()).isTrue();

        part.clickDiscard();
        assertThat(part.getUserNameField().getValue()).isEmpty();
        assertThat(workspace.isNavigationLocked()).isFalse();

        part.getAssistantNameField().setValue("Kairo");
        assertThat(workspace.isNavigationLocked()).isTrue();
        part.clickSave();
        assertThat(workspace.isNavigationLocked()).isFalse();
        assertThat(setting().getAssistantName()).isEqualTo("Kairo");
    }

    @Test
    void theDialogsStoreTheUsersVersionsAndKeepKeysUnique() throws Exception {
        PersonalityDialog builtIn = new PersonalityDialog("cheerful");
        builtIn.create();
        assertThat(builtIn.getPromptArea().getValue()).isEmpty();
        assertThat(builtIn.getPromptArea().getPlaceholder()).isEqualTo(personaService.defaults().personalities().get(2).prompt());
        builtIn.getExpressionArea("sleepy").setValue("Sleepy but sparkly.");
        builtIn.clickSave();
        assertThat(personaService.personality("cheerful").moodExpressions()).containsEntry("sleepy", "Sleepy but sparkly.");

        PersonalityDialog taken = new PersonalityDialog(null);
        taken.create();
        taken.getKeyField().setValue("warm");
        taken.clickSave();
        assertThat(taken.getKeyField().isInvalid()).isTrue();

        MoodDialog mood = new MoodDialog(null);
        mood.create();
        mood.getKeyField().setValue("nostalgic");
        mood.getPromptArea().setValue("You are nostalgic today.");
        mood.getWeightField().setValue(2);
        mood.clickSave();
        assertThat(personaService.mood("nostalgic").weight()).isEqualTo(2);
        assertThat(personaService.mood("nostalgic").builtIn()).isFalse();

        SystemPromptPreviewDialog preview = new SystemPromptPreviewDialog(null);
        preview.create();
        assertThat(preview.getText()).contains("You are Joi").contains("WHAT YOU REMEMBER").doesNotContain("{{");
    }

    @Test
    void theWorkspaceHasTheSettingsPart() throws Exception {
        JoiWorkspace workspace = new JoiWorkspace();
        workspace.create();
        assertThat(workspace.getPart("settings")).isInstanceOf(UserSettingsPart.class);
    }
}
