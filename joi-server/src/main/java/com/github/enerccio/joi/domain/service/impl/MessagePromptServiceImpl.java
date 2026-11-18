package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.MessagePrompt;
import com.github.enerccio.joi.domain.repository.MessagePromptRepository;
import com.github.enerccio.joi.domain.service.MessagePromptService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

public class MessagePromptServiceImpl extends ExtendableServiceImpl<MessagePrompt, MessagePromptRepository> implements MessagePromptService {

    @Override
    @CommonTxReadOnly
    public MessagePrompt findByMessage(ChatMessage message) throws Exception {
        return getRepository().findByMessage(message);
    }

    @Override
    @CommonTx
    public MessagePrompt store(ChatMessage message, String builtPrompt) throws Exception {
        MessagePrompt prompt = getRepository().findByMessage(message);
        if (prompt == null) {
            prompt = new MessagePrompt();
            prompt.setMessage(message);
        }
        prompt.setBuiltPrompt(builtPrompt);
        return save(prompt);
    }
}
