package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.persona.Personality;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.joi.ui.dialogs.joi.JoiAboutPart;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jois part (the user's assistants, by name, with the last chat) and the Joi dialog: About and Templates store as
 * they change, empty fields show the user's settings, and a change of the persona becomes an event only once she has
 * chatted.
 */
class JoisPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private PersonaService personaService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private Localization loc;

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

    private void chat(Joi joi, long at) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date(at));
        chatMessageService.save(message);
    }

    private JoiDialog dialog(Joi joi) throws Exception {
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        return dialog;
    }

    @Test
    void listsTheUsersJoisByNameWithTheirLastChat() throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        setting.setAssistantName("Mira");
        settingService.save(setting);
        Joi kairo = joiService.create("Kairo");
        Joi inherits = joiService.create("  ");
        chat(kairo, 1_000L);
        chat(kairo, 5_000L);
        login();
        joiService.create("Someone else's");
        loginAs(kairo.getOwner());

        JoisPart part = new JoisPart();
        part.create();
        part.refresh();

        List<Joi> rows = part.getGrid().getDataProvider().fetch(new Query<>()).toList();
        assertThat(rows).extracting(Joi::getId).containsExactly(kairo.getId(), inherits.getId());
        assertThat(joiService.name(rows.get(1))).isEqualTo("Mira");
        assertThat(chatMessageService.findLastSentAt(rows)).containsOnlyKeys(kairo.getId());
        assertThat(chatMessageService.findLastSentAt(rows).get(kairo.getId())).hasSameTimeAs(new Date(5_000L));
    }

    @Test
    void theDialogHasItsTabsAndShowsTheInheritedValues() throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        setting.setUserName("Pat");
        setting.setSummaryPrompt("The user's summary: {{conversation}}");
        settingService.save(setting);

        JoiDialog dialog = dialog(joiService.create("Kairo"));

        assertThat(dialog.getHeaderTitle()).isEqualTo("Kairo");
        assertThat(dialog.getTabs().getTabCount()).isEqualTo(5);
        assertThat(dialog.getTabs().getTabAt(0).getLabel()).isEqualTo(loc.getValue(J_L.LABEL_ABOUT));
        assertThat(dialog.getTabs().getTabAt(1).getLabel()).isEqualTo(loc.getValue(J_L.LABEL_TEMPLATES));
        assertThat(dialog.getTabs().getTabAt(4).getLabel()).isEqualTo(loc.getValue(J_L.LABEL_CHATS));
        JoiAboutPart about = dialog.getAboutPart();
        assertThat(about.getNameField().getValue()).isEqualTo("Kairo");
        assertThat(about.getUserNameField().getValue()).isEmpty();
        assertThat(about.getUserNameField().getPlaceholder()).isEqualTo("Pat");
        assertThat(dialog.getTemplatesPart().getArea(JoiPromptField.SUMMARY_PROMPT).getValue()).isEmpty();
        assertThat(dialog.getTemplatesPart().getArea(JoiPromptField.SUMMARY_PROMPT).getPlaceholder())
                .isEqualTo("The user's summary: {{conversation}}");
    }

    @Test
    void aboutStoresAtOnceAndTheSetupMakesNoEvents() throws Exception {
        Joi joi = joiService.create("Kairo");
        JoiDialog dialog = dialog(joi);

        dialog.getAboutPart().getNameField().setValue("Nova");
        dialog.getAboutPart().getUserNameField().setValue("Captain");

        Joi stored = joiService.find(joi.getId());
        assertThat(stored.getAssistantName()).isEqualTo("Nova");
        assertThat(stored.getUserName()).isEqualTo("Captain");
        assertThat(dialog.getHeaderTitle()).isEqualTo("Nova");
        assertThat(queuedEventService.findWaiting(stored)).isEmpty();
    }

    @Test
    void personaChangesBecomeEventsOnceSheHasChatted() throws Exception {
        Joi joi = joiService.create("Kairo");
        chat(joi, 1_000L);
        JoiDialog dialog = dialog(joi);
        JoiAboutPart about = dialog.getAboutPart();

        about.getNameField().setValue("Nova");
        about.getUserNameField().setValue("Captain");
        Personality other = about.getPersonalitySelect().getListDataView().getItems()
                .filter(p -> p.key().equals("tsundere")).findFirst().orElseThrow();
        about.getPersonalitySelect().setValue(other);
        // not part of the persona: no event
        about.getModelBox(com.github.enerccio.joi.domain.collections.TextLLMType.CHAT).clear();

        List<QueuedEvent> events = queuedEventService.findWaiting(joiService.find(joi.getId()));
        assertThat(events).extracting(QueuedEvent::getKind)
                .containsExactly(JoiEvents.RENAMED, JoiEvents.USER_RENAMED, JoiEvents.PERSONALITY_CHANGED);
        assertThat(events.getFirst().getText()).isEqualTo("You were renamed from Kairo to Nova.");
        assertThat(events.get(1).getText()).contains("Captain");
        assertThat(events.get(2).getPayload()).contains("tsundere");
    }

    @Test
    void templatesStoreValidOnesOnly() throws Exception {
        Joi joi = joiService.create("Kairo");
        JoiDialog dialog = dialog(joi);

        dialog.getTemplatesPart().getArea(JoiPromptField.FACTS_PROMPT).setValue("Her facts: {{conversation}}");
        assertThat(joiService.find(joi.getId()).getFactsPrompt()).isEqualTo("Her facts: {{conversation}}");

        dialog.getTemplatesPart().getArea(JoiPromptField.FACTS_PROMPT).setValue("{{#if}}broken");
        assertThat(joiService.find(joi.getId()).getFactsPrompt()).isEqualTo("Her facts: {{conversation}}");

        dialog.getTemplatesPart().getArea(JoiPromptField.FACTS_PROMPT).setValue("");
        assertThat(joiService.find(joi.getId()).getFactsPrompt()).isNull();
    }

    private static List<TextField> textFields(Component root) {
        List<TextField> found = new java.util.ArrayList<>();
        root.getChildren().forEach(child -> {
            if (child instanceof TextField field) {
                found.add(field);
            }
            found.addAll(textFields(child));
        });
        return found;
    }

    @Test
    void createJoiAsksWithTheNameOfTheSettings() throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        setting.setAssistantName("Mira");
        settingService.save(setting);
        JoisPart part = new JoisPart();
        part.create();

        assertThat(textFields(part.nameDialog())).extracting(TextField::getValue).containsExactly("Mira");
    }
}
