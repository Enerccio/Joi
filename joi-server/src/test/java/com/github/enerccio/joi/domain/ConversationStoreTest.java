package com.github.enerccio.joi.domain;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.collections.*;
import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.*;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queries of the conversation, the summaries, the tasks, the memories and the queued events, and the assistant as
 * the first link of the chain of a setting.
 */
class ConversationStoreTest extends PlatformTestBase {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private MessagePromptService messagePromptService;
    @Autowired
    private SummaryService summaryService;
    @Autowired
    private JoiTaskService joiTaskService;
    @Autowired
    private JoiTaskMessageService joiTaskMessageService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private QueuedEventService queuedEventService;

    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.save(new Joi());
    }

    private ChatMessage message(long at, String input) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date(at));
        message.setInput(input);
        return chatMessageService.save(message);
    }

    private Summary summary(SummaryType type, long from, long to) throws Exception {
        Summary summary = new Summary();
        summary.setJoi(joi);
        summary.setSummaryType(type);
        summary.setCoverFrom(new Date(from));
        summary.setCoverTo(new Date(to));
        summary.setSummary(type + " " + from);
        return summaryService.save(summary);
    }

    @Test
    void messagesByTimeAndLatest() throws Exception {
        message(3 * HOUR, "third");
        message(HOUR, "first");
        message(2 * HOUR, "second");
        Joi other = joiService.save(new Joi());
        ChatMessage elsewhere = new ChatMessage();
        elsewhere.setJoi(other);
        elsewhere.setSentAt(new Date(2 * HOUR));
        chatMessageService.save(elsewhere);

        assertThat(chatMessageService.findBetween(joi, new Date(HOUR), new Date(3 * HOUR)))
                .extracting(ChatMessage::getInput).containsExactly("first", "second");
        assertThat(chatMessageService.findLatest(joi, 2)).extracting(ChatMessage::getInput).containsExactly("second", "third");
    }

    @Test
    void onePromptPerMessage() throws Exception {
        ChatMessage message = message(HOUR, "hi");

        messagePromptService.store(message, "first prompt");
        messagePromptService.store(message, "second prompt");

        assertThat(messagePromptService.findByMessage(message).getBuiltPrompt()).isEqualTo("second prompt");
        assertThat(messagePromptService.findAllForUser()).hasSize(1);
    }

    @Test
    void longerSummariesCoverTheShorterOnes() throws Exception {
        // a week of days 0..6, the first three days also in a rolled-up week; day 7 is after the week
        Summary week = summary(SummaryType.WEEK, 0, 7 * DAY);
        for (int day = 0; day < 8; day++) {
            summary(SummaryType.DAY, day * DAY, (day + 1) * DAY);
        }
        // the rolling summary of day 7 is in its day summary; the one of day 8 is not covered yet
        summary(SummaryType.ROLLING, 7 * DAY, 7 * DAY + HOUR);
        Summary rolling = summary(SummaryType.ROLLING, 8 * DAY, 8 * DAY + HOUR);

        List<Summary> shown = summaryService.findForPrompt(joi, new Date(0), new Date(9 * DAY));

        assertThat(shown).extracting(Summary::getSummary)
                .containsExactly(week.getSummary(), "DAY " + 7 * DAY, rolling.getSummary());
    }

    @Test
    void summariesOfTheSameRankDoNotHideEachOther() {
        Summary a = new Summary();
        a.setSummaryType(SummaryType.DAY);
        a.setCoverFrom(new Date(0));
        a.setCoverTo(new Date(DAY));
        Summary b = new Summary();
        b.setSummaryType(SummaryType.DAY);
        b.setCoverFrom(new Date(HOUR));
        b.setCoverTo(new Date(2 * HOUR));

        assertThat(SummaryCoverage.uncovered(List.of(b, a))).containsExactly(a, b);
    }

    @Test
    void queuedTasksAndTheirTranscript() throws Exception {
        JoiTask queued = new JoiTask();
        queued.setJoi(joi);
        queued.setOriginMessage(message(HOUR, "compare the GPUs"));
        queued = joiTaskService.save(queued);
        JoiTask done = new JoiTask();
        done.setJoi(joi);
        done.setStatus(JoiTaskStatus.DONE);
        joiTaskService.save(done);

        JoiTaskMessage first = new JoiTaskMessage();
        first.setRole(TaskMessageRole.USER);
        first.setContent("instructions");
        joiTaskMessageService.append(queued, first);
        JoiTaskMessage second = new JoiTaskMessage();
        second.setContent("answer");
        joiTaskMessageService.append(queued, second);

        assertThat(joiTaskService.findQueued(joi)).extracting(JoiTask::getId).containsExactly(queued.getId());
        assertThat(joiTaskMessageService.findByTask(queued)).extracting(JoiTaskMessage::getContent, JoiTaskMessage::getOrdinal)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("instructions", 0), org.assertj.core.groups.Tuple.tuple("answer", 1));
    }

    @Test
    void memoriesByImportance() throws Exception {
        for (MemoryImportance importance : MemoryImportance.values()) {
            Memory memory = new Memory();
            memory.setJoi(joi);
            memory.setImportance(importance);
            memory.setText(importance.name());
            memoryService.save(memory);
        }

        assertThat(memoryService.findByImportance(joi, MemoryImportance.IMPORTANT)).extracting(Memory::getText)
                .containsExactly("IMPORTANT");
    }

    @Test
    void queuedEventsWaitUntilDelivered() throws Exception {
        QueuedEvent later = event("setting.changed", 2 * HOUR);
        QueuedEvent earlier = event("model.added", HOUR);

        assertThat(queuedEventService.findWaiting(joi)).extracting(QueuedEvent::getKind)
                .containsExactly("model.added", "setting.changed");

        ChatMessage delivered = message(3 * HOUR, null);
        queuedEventService.markDelivered(earlier, delivered);

        assertThat(queuedEventService.findWaiting(joi)).extracting(QueuedEvent::getId).containsExactly(later.getId());
        assertThat(queuedEventService.find(earlier.getId()).getDeliveredIn().getId()).isEqualTo(delivered.getId());
    }

    private QueuedEvent event(String kind, long at) throws Exception {
        QueuedEvent event = new QueuedEvent();
        event.setJoi(joi);
        event.setKind(kind);
        event.setOccurredAt(new Date(at));
        return queuedEventService.save(event);
    }

    @Test
    void theAssistantIsTheFirstLinkOfTheChain() {
        JoiDefaults defaults = new JoiDefaults();
        JoiUserSetting setting = new JoiUserSetting();
        setting.setSummaryPrompt("The user's");
        Joi assistant = new Joi();

        assertThat(JoiPromptField.SUMMARY_PROMPT.resolve(assistant, setting, defaults)).isEqualTo("The user's");
        assistant.setSummaryPrompt("Hers");
        assertThat(JoiPromptField.SUMMARY_PROMPT.resolve(assistant, setting, defaults)).isEqualTo("Hers");
        assertThat(JoiPromptField.SUMMARY_PROMPT.resolve(assistant, null, defaults)).isEqualTo("Hers");
    }
}
