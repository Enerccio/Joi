package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageCrudTest extends ExtendableCrudContract<ChatMessage> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Override
    protected OwnedService<ChatMessage, ?> service() {
        return chatMessageService;
    }

    @Override
    protected ChatMessage newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setType(ChatMessageType.EVENT);
        message.setEventKind("model.added");
        message.setSentAt(new Date(1_000_000L));
        message.setInput("A new model was added.");
        message.setResponse("Ooh, a new brain?");
        message.setResponseReasoning("The user added a model; react with curiosity.");
        message.setTokenReasoningCount(12L);
        message.setPrivateMode(true);
        return message;
    }

    @Override
    protected void assertCreated(ChatMessage loaded) {
        assertThat(loaded.getJoi()).isNotNull();
        assertThat(loaded.getType()).isEqualTo(ChatMessageType.EVENT);
        assertThat(loaded.getEventKind()).isEqualTo("model.added");
        assertThat(loaded.getSentAt()).hasSameTimeAs(new Date(1_000_000L));
        assertThat(loaded.getInput()).isEqualTo("A new model was added.");
        assertThat(loaded.getResponse()).isEqualTo("Ooh, a new brain?");
        assertThat(loaded.getResponseReasoning()).isEqualTo("The user added a model; react with curiosity.");
        assertThat(loaded.getTokenReasoningCount()).isEqualTo(12L);
        assertThat(loaded.isPrivateMode()).isTrue();
        assertThat(loaded.isPinned()).isFalse();
    }

    @Override
    protected void modify(ChatMessage entity) {
        entity.setResponse("Edited.");
        entity.setOriginalResponse("Ooh, a new brain?");
        entity.setPinned(true);
    }

    @Override
    protected void assertModified(ChatMessage loaded) {
        assertThat(loaded.getResponse()).isEqualTo("Edited.");
        assertThat(loaded.getOriginalResponse()).isEqualTo("Ooh, a new brain?");
        assertThat(loaded.getResponseReasoning()).isEqualTo("The user added a model; react with curiosity.");
        assertThat(loaded.isPinned()).isTrue();
    }
}
