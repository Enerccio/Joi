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
    @Autowired
    private com.github.enerccio.joi.domain.service.SummaryService summaryService;
    @Autowired
    private com.github.enerccio.joi.domain.chat.ChatService chatService;
    @Autowired
    private com.github.enerccio.joi.domain.service.ChatImageService imageService;
    @Autowired
    private com.github.enerccio.marginalia.domain.service.AIService aiService;

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
    void aNoteOfTheSystemIsShownAsANoteAmongTheExchanges() throws Exception {
        Joi joi = joiService.create("Mira");
        chat(joi, 1, 1_000_000L);
        ChatMessage note = chatService.log(joi, "task.approved", "Approved by the user: web_search \"x\"");

        JoiChatsPart part = chats(joi);
        assertThat(part.getShown()).extracting(ChatMessage::getType).containsExactly(ChatMessageType.USER, ChatMessageType.LOG);
        com.vaadin.flow.component.Component card = part.card(note);
        assertThat(card.getElement().getOuterHTML()).contains("chat-input-log").contains("Approved by the user");
        assertThat(card.getElement().getOuterHTML()).doesNotContain("chat-reply");
    }

    @Test
    void aMessageWithAnImageShowsItAndTheUserCanSendAnImageWithWordsToHer() throws Exception {
        Joi joi = joiService.create("Mira");
        com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible ai = createAI();
        ai.setMultimodal(true);
        ai = (com.github.enerccio.marginalia.domain.model.impl.OpenAICompatible) aiService.save(ai);
        joi.setChatModel(ai.getId());
        joi = joiService.save(joi);
        java.io.File file = imageService.newUploadFile();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file);
        String image = imageService.store(file).id();
        llm.enqueue(com.github.enerccio.marginalia.test.llm.MockLLMResponse.text("A black picture."));
        JoiChatsPart part = chats(joi);

        part.sendForTest("What is this?", java.util.List.of(image));

        // the turn runs in the background; the exchange comes with its image
        long end = System.currentTimeMillis() + 30_000;
        while (chatMessageService.findLatest(joi, 10).isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        ChatMessage stored = chatMessageService.findLatest(joi, 10).getFirst();
        assertThat(stored.getInput()).isEqualTo("What is this?");
        assertThat(stored.imageUuids()).containsExactly(image);
        assertThat(stored.getNode()).isNull();
        assertThat(llm.getCompletionRequests().getFirst().getRawBody()).contains("\"type\":\"image_url\"");
        String html = part.card(stored).getElement().getOuterHTML();
        assertThat(html).contains("chat-image").contains("What is this?");
        assertThat(stored.getResponse()).isEqualTo("A black picture.");
    }

    @Test
    void thePicturesOfHerToolsAreShownWithHerReply() throws Exception {
        Joi joi = joiService.create("Mira");
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(com.github.enerccio.joi.domain.collections.ChatMessageType.USER);
        message.setInput("Look at my screen");
        message.setResponse("A page about cats.");
        message.setSentAt(new java.util.Date());
        message.setToolImages(ChatMessage.toJson(java.util.List.of("a-picture")));
        message = chatMessageService.save(message);
        com.vaadin.flow.dom.Element card = chats(joi).card(message).getElement();
        // in her reply, not with the user's words
        com.vaadin.flow.dom.Element reply = card.getChildren().filter(child -> String.valueOf(child.getAttribute("class")).contains("chat-reply"))
                .findFirst().orElseThrow();
        assertThat(reply.getOuterHTML()).contains("chat-image").contains("A picture her tool brought").contains("chat-markdown");
        assertThat(card.getOuterHTML().indexOf("chat-image")).isGreaterThan(card.getOuterHTML().indexOf("Look at my screen"));
    }

    @Test
    void theMessagesASummaryCoversAreFaded() throws Exception {
        Joi joi = joiService.create("Mira");
        for (int n = 0; n < 4; n++) {
            chat(joi, n, 1_000_000L + n * 1000L);
        }
        // the summary covers the first two
        com.github.enerccio.joi.domain.model.impl.Summary summary = new com.github.enerccio.joi.domain.model.impl.Summary();
        summary.setJoi(joi);
        summary.setSummaryType(com.github.enerccio.joi.domain.collections.SummaryType.DAY);
        summary.setCoverFrom(new java.util.Date(0));
        summary.setCoverTo(new java.util.Date(1_001_500L));
        summary.setSummary("They said hello.");
        summaryService.save(summary);

        JoiChatsPart part = chats(joi);
        java.util.List<ChatMessage> shown = java.util.List.copyOf(part.getShown());
        assertThat(shown).hasSize(4);
        for (int n = 0; n < 4; n++) {
            String html = part.card(shown.get(n)).getElement().getOuterHTML();
            if (n < 2) {
                assertThat(html).contains("chat-message-summarized");
            } else {
                assertThat(html).doesNotContain("chat-message-summarized");
            }
        }
    }

    @Test
    void shiftClickChecksARangeAndTheCheckedAreDeleted() throws Exception {
        Joi joi = joiService.create("Mira");
        Joi other = joiService.create("Kairo");
        for (int n = 0; n < 6; n++) {
            chat(joi, n, 1_000_000L + n * 1000L);
        }
        chat(other, 99, 1_000_000L);

        JoiChatsPart part = chats(joi);
        java.util.List<ChatMessage> shown = java.util.List.copyOf(part.getShown());
        part.clickForTest(shown.get(1), false);
        part.clickForTest(shown.get(4), true);
        assertThat(part.getChecked()).containsExactlyInAnyOrder(shown.get(1).getId(), shown.get(2).getId(),
                shown.get(3).getId(), shown.get(4).getId());
        // a shift-click on a checked one unchecks the range back to it
        part.clickForTest(shown.get(3), true);
        assertThat(part.getChecked()).containsExactlyInAnyOrder(shown.get(1).getId(), shown.get(2).getId());
        part.clickForTest(shown.get(5), false);

        part.deleteCheckedForTest();
        assertThat(part.getChecked()).isEmpty();
        assertThat(part.getShown()).extracting(ChatMessage::getInput)
                .containsExactly("question 0", "question 3", "question 4");
        assertThat(chatMessageService.findLatest(joi, 10)).extracting(ChatMessage::getInput)
                .containsExactly("question 0", "question 3", "question 4");

        // another assistant's message is not hers to delete
        ChatMessage foreign = chatMessageService.findLatest(other, 1).getFirst();
        assertThat(chatMessageService.deleteMessages(joi, java.util.List.of(foreign.getId()))).isZero();
        assertThat(chatMessageService.hasAny(other)).isTrue();
    }

    @Test
    void anAssistantWhoNeverChattedHasAnEmptyTab() throws Exception {
        assertThat(chats(joiService.create("Mira")).getShown()).isEmpty();
    }

    @Test
    void aClipIsTheMessagesResourceAndIsFoundAgain() throws Exception {
        Joi joi = joiService.create("Clippy");
        chat(joi, 1, 1000);
        ChatMessage message = chatMessageService.findLatest(joi, 1).getFirst();
        assertThat(chatMessageService.clipFile(message)).isNull();

        java.io.File part = chatMessageService.newClipFile();
        java.nio.file.Files.write(part.toPath(), new byte[]{1, 2, 3});
        ChatMessage voiced = chatMessageService.attachClip(message, part, "mp3", "audio/mpeg");

        assertThat(voiced.getClipResourceId()).isNotNull();
        ChatMessage again = chatMessageService.find(message.getId());
        assertThat(chatMessageService.clipFile(again)).isFile().hasSize(3);
        assertThat(chatMessageService.clipMime(again)).isEqualTo("audio/mpeg");
        assertThat(part).doesNotExist();
    }

    @Test
    void aMessageListsEveryToolUsedInItsReply() throws Exception {
        Joi joi = joiService.create("Mira");
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(com.github.enerccio.joi.domain.collections.ChatMessageType.USER);
        message.setInput("Wave and remember Mia.");
        message.setResponse("Hi!");
        message.setSentAt(new java.util.Date(1000));
        message.setToolCalls(com.github.enerccio.joi.domain.chat.ToolUsage.toJson(java.util.List.of(
                new com.github.enerccio.joi.domain.chat.ToolUsage("play_animation", "{\"animation\":\"Wave\"}", "You played Wave.", false),
                new com.github.enerccio.joi.domain.chat.ToolUsage("desk__note", "{}", "no such folder", true))));
        message = chatMessageService.save(message);

        String text = chats(joi).card(message).getElement().getTextRecursively();
        assertThat(text).contains("Tools used (2): play_animation, desk__note").contains("{\"animation\":\"Wave\"}")
                .contains("You played Wave.").contains("desk__note (failed)").contains("no such folder");
        // a message without tools has no list
        ChatMessage plain = chatMessageService.find(message.getId());
        plain.setToolCalls(null);
        assertThat(chats(joi).card(plain).getElement().getTextRecursively()).doesNotContain("Tools used");
    }
}
