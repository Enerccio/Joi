package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.github.enerccio.marginalia.test.llm.MockLLMResponse;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.markdown.Markdown;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** The Summaries tab, the Summarize button of the Chats tab and the summary settings of her About tab. */
class JoiSummariesTest extends PlatformTestBase {

    private static final long HOUR = 3_600_000L;

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessages;
    @Autowired
    private SummaryService summaries;
    @Autowired
    private SummarizerService summarizer;

    private UI ui;
    private Joi joi;
    private AI model;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        model = createAI();
        joi = joiService.create("Mira");
        joi.setChatModel(model.getId());
        joi = joiService.save(joi);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private ChatMessage message(long at, String text) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date(at));
        message.setInput(text);
        message.setResponse("re: " + text);
        return chatMessages.save(message);
    }

    private Summary summary(SummaryType type, long from, long to, String text) throws Exception {
        Summary summary = new Summary();
        summary.setJoi(joi);
        summary.setSummaryType(type);
        summary.setCoverFrom(new Date(from));
        summary.setCoverTo(new Date(to));
        summary.setSummary(text);
        return summaries.save(summary);
    }

    private JoiDialog dialog() throws Exception {
        JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
        dialog.create();
        return dialog;
    }

    private <T> T await(Callable<T> found, String what) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            T value = found.call();
            if (value != null) {
                return value;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Waited for " + what);
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void theSummariesTabListsWhatSheRemembersAndDimsWhatALongerSummaryCovers() throws Exception {
        long day = 24 * HOUR;
        summary(SummaryType.DAY, 10 * day, 11 * day, "Monday.");
        summary(SummaryType.DAY, 11 * day, 12 * day, "Tuesday.");
        summary(SummaryType.WEEK, 10 * day, 17 * day, "The week.");
        summary(SummaryType.DAY, 20 * day, 21 * day, "Later.");

        JoiSummariesPart part = dialog().getSummariesPart();
        assertThat(part.getShown()).isEqualTo(4);
        List<Component> cards = part.getList().getChildren().toList();
        List<String> texts = cards.stream().map(card -> card.getChildren().filter(Markdown.class::isInstance).map(m -> ((Markdown) m).getContent())
                .findFirst().orElse("")).toList();
        // the latest start first
        assertThat(texts).containsExactly("Later.", "Tuesday.", "The week.", "Monday.");
        // the days inside the week are covered, the week and the later day are what her prompt shows
        assertThat(cards.stream().filter(card -> card.hasClassName("summary-covered")).count()).isEqualTo(2);
        assertThat(cards.get(1).hasClassName("summary-covered")).isTrue();
        assertThat(cards.get(2).hasClassName("summary-covered")).isFalse();
    }

    @Test
    void aSummaryIsDeletedFromTheTab() throws Exception {
        Summary one = summary(SummaryType.DAY, 0, 24 * HOUR, "Gone soon.");
        JoiSummariesPart part = dialog().getSummariesPart();
        assertThat(part.getShown()).isEqualTo(1);
        summaries.delete(one, false);
        part.shown();
        assertThat(part.getShown()).isZero();
    }

    @Test
    void summarizeChecksEveryMessageBeforeTheLastCheckedOneAndAsksFirst() throws Exception {
        long start = System.currentTimeMillis() - 10 * HOUR;
        ChatMessage a = message(start, "a");
        ChatMessage b = message(start + 1000, "b");
        ChatMessage c = message(start + 2000, "c");
        ChatMessage d = message(start + 3000, "d");
        JoiChatsPart chats = dialog().getChatsPart();

        // only the third is checked: the first two are in the range too, the fourth is not
        chats.clickForTest(c, false);
        assertThat(chats.getChecked()).containsExactly(c.getId());
        llm.enqueue(MockLLMResponse.text("a, b and c."));
        chats.summarizeForTest(false);
        // cancelled: the selection is as it was
        assertThat(chats.getChecked()).containsExactly(c.getId());
        assertThat(summarizer.jobs(joi)).isEmpty();

        chats.summarizeForTest(true);
        assertThat(chats.getChecked()).isEmpty();
        List<Summary> made = await(() -> summaries.findAllOf(joi).isEmpty() ? null : summaries.findAllOf(joi), "the summary");
        assertThat(made.getFirst().getMessageCount()).isEqualTo(3);
        assertThat(made.getFirst().getSummary()).isEqualTo("a, b and c.");
        assertThat(summarizer.rangeUpTo(joi, d)).extracting(ChatMessage::getId).containsExactly(d.getId());
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    void herAboutTabKeepsTheSummarySettingsAndShowsWhatTheUserSettingGives() throws Exception {
        JoiAboutPart about = dialog().getAboutPart();
        var fields = about.getSummaryFields();
        assertThat(fields.getAutoSummary().getValue()).isNull();
        assertThat(fields.getPause().getValue()).isNull();

        fields.getPause().setValue(90);
        fields.getAutoSummary().setValue(Boolean.TRUE);
        fields.getAutoTokens().setValue(8000);
        fields.getWeekly().setValue(LocalTime.of(6, 30));
        fields.getSummaryModel().setValue(model);

        Joi stored = joiService.find(joi.getId());
        assertThat(stored.getPauseMinutes()).isEqualTo(90);
        assertThat(stored.getAutoSummary()).isTrue();
        assertThat(stored.getAutoSummaryTokens()).isEqualTo(8000);
        assertThat(stored.getWeeklySummaryTime()).isEqualTo("06:30");
        assertThat(stored.getMonthlySummaryTime()).isNull();
        assertThat(stored.getSummaryModel()).isEqualTo(model.getId());
    }
}
