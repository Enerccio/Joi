package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.MessagePrompt;
import com.github.enerccio.joi.domain.repository.MessagePromptRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

/** The prompts sent for the messages. */
public interface MessagePromptService extends ExtendableService<MessagePrompt, MessagePromptRepository> {

    /** The prompt of the message, null when none was stored. */
    MessagePrompt findByMessage(ChatMessage message) throws Exception;

    /** Stores the prompt of the message, in place of the one it had (at most one per message). */
    MessagePrompt store(ChatMessage message, String builtPrompt) throws Exception;
}
