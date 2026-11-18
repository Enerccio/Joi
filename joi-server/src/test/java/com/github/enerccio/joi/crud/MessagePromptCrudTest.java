package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.MessagePrompt;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.MessagePromptService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePromptCrudTest extends ExtendableCrudContract<MessagePrompt> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MessagePromptService messagePromptService;

    @Override
    protected OwnedService<MessagePrompt, ?> service() {
        return messagePromptService;
    }

    @Override
    protected MessagePrompt newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        ChatMessage message = new ChatMessage();
        message.setJoi(joi);
        message.setSentAt(new Date());
        message = chatMessageService.save(message);
        MessagePrompt prompt = new MessagePrompt();
        prompt.setMessage(message);
        prompt.setBuiltPrompt("{\"messages\": [\"" + "persona ".repeat(500) + "\"]}");
        prompt.setRecalledMemories("[\"m1\"]");
        return prompt;
    }

    @Override
    protected void assertCreated(MessagePrompt loaded) {
        assertThat(loaded.getMessage()).isNotNull();
        assertThat(loaded.getBuiltPrompt()).startsWith("{\"messages\": [\"persona ").endsWith("\"]}");
        assertThat(loaded.getRecalledMemories()).isEqualTo("[\"m1\"]");
    }

    @Override
    protected void modify(MessagePrompt entity) {
        entity.setBuiltPrompt("short");
    }

    @Override
    protected void assertModified(MessagePrompt loaded) {
        assertThat(loaded.getBuiltPrompt()).isEqualTo("short");
        assertThat(loaded.getRecalledMemories()).isEqualTo("[\"m1\"]");
    }
}
