package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** The Chats tab: the last page of her conversation, and the earlier pages as the user scrolls up. */
class JoiChatsPartTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;

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

    private void chat(Joi joi, int n, long at) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date(at));
        message.setInput("question " + n);
        message.setResponse("answer " + n);
        if (n % 2 == 0) {
            message.setResponseReasoning("thinking " + n);
        }
        chatMessageService.save(message);
    }

    private JoiChatsPart chats(Joi joi) throws Exception {
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        return dialog.getChatsPart();
    }

    @Test
    void showsTheLastPageAndLoadsEarlierPagesOnTheWayUp() throws Exception {
        Joi joi = joiService.create("Mira");
        Joi other = joiService.create("Kairo");
        int total = JoiChatsPart.PAGE * 2 + 5;
        for (int n = 0; n < total; n++) {
            // two messages share each second: the order within it is the order they were stored
            chat(joi, n, 1_000_000L + (n / 2) * 1000L);
        }
        chat(other, 999, 5_000_000L);

        JoiChatsPart part = chats(joi);
        assertThat(part.getShown()).hasSize(JoiChatsPart.PAGE);
        assertThat(part.getShown().getLast().getInput()).isEqualTo("question " + (total - 1));
        assertThat(part.getShown().getFirst().getInput()).isEqualTo("question " + (total - JoiChatsPart.PAGE));

        part.scrolledToTop();
        assertThat(part.getShown()).hasSize(JoiChatsPart.PAGE * 2);
        part.scrolledToTop();
        assertThat(part.getShown()).hasSize(total);
        assertThat(part.getShown()).extracting(ChatMessage::getInput)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, total).mapToObj(n -> "question " + n).toList());
        part.scrolledToTop();
        assertThat(part.getShown()).hasSize(total);
    }

    @Test
    void anAssistantWhoNeverChattedHasAnEmptyTab() throws Exception {
        assertThat(chats(joiService.create("Mira")).getShown()).isEmpty();
    }
}
