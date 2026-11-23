package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.marginalia.domain.service.CleanupService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administrator's cleanup purges what was deleted of an assistant: the platform nulls the references of the purged rows
 * before it removes them, so the columns of her owner are nullable (they were not, and the purge failed).
 */
class JoiCleanupTest extends PlatformTestBase {

    @Autowired
    private JoiService joiService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private SummaryService summaryService;
    @Autowired
    private QueuedEventService queuedEventService;
    @Autowired
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        loginAdmin();
    }

    private ChatMessage message(Joi joi, String input) throws Exception {
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.USER);
        message.setSentAt(new Date());
        message.setInput(input);
        message.setResponse("Yes.");
        return chatMessageService.save(message);
    }

    @Test
    void deletedMessagesAndADeletedAssistantWithAllOfHersArePurged() throws Exception {
        Joi kept = joiService.create("Mira");
        ChatMessage stays = message(kept, "Hello");
        ChatMessage gone = message(kept, "Forget this");
        chatMessageService.deleteMessages(kept, List.of(gone.getId()));

        Joi deleted = joiService.create("Kairo");
        message(deleted, "Bye");
        Memory memory = memoryService.create(deleted);
        memory.setName("m");
        memoryService.update(deleted, memory);
        Summary summary = new Summary();
        summary.setJoi(deleted);
        summary.setSummaryType(SummaryType.DAY);
        summary.setCoverFrom(new Date(0));
        summary.setCoverTo(new Date());
        summary.setSummary("A day.");
        summaryService.save(summary);
        QueuedEvent event = new QueuedEvent();
        event.setJoi(deleted);
        event.setKind(JoiEvents.RENAMED);
        event.setOccurredAt(new Date());
        event.setText("You are now called Kairo.");
        queuedEventService.save(event);
        joiService.delete(joiService.find(deleted.getId()), false);

        CleanupService.CleanupPlan plan = cleanupService.purge();

        assertThat(plan.isExecuted()).isTrue();
        assertThat(plan.getPurge()).isNotEmpty();
        assertThat(chatMessageService.findLatest(kept, 10)).extracting(ChatMessage::getId).containsExactly(stays.getId());
        assertThat(cleanupService.analyze().getPurge()).isEmpty();
    }
}
