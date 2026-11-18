package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.MessagePrompt;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

/** The prompts sent for the messages. */
public interface MessagePromptRepository extends ExtendableRepository<MessagePrompt> {

    /** The prompt of the message, null when none was stored. */
    MessagePrompt findByMessage(ChatMessage message) throws Exception;
}
