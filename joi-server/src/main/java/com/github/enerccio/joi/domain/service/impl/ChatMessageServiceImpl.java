package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.ChatMessageRepository;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ChatMessageServiceImpl extends ExtendableServiceImpl<ChatMessage, ChatMessageRepository> implements ChatMessageService {

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findBetween(Joi joi, Date from, Date to) throws Exception {
        return getRepository().findBetween(joi, from, to);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findLatest(Joi joi, int limit) throws Exception {
        return getRepository().findLatest(joi, limit);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findBefore(Joi joi, ChatMessage first, int limit) throws Exception {
        return getRepository().findBefore(joi, first, limit);
    }

    @Override
    @CommonTxReadOnly
    public Map<Long, Date> findLastSentAt(Collection<Joi> jois) throws Exception {
        return getRepository().findLastSentAt(jois);
    }

    @Override
    @CommonTxReadOnly
    public boolean hasAny(Joi joi) throws Exception {
        return getRepository().hasAny(joi);
    }

    @Override
    @CommonTx
    public int deleteMessages(Joi joi, Collection<Long> ids) throws Exception {
        int deleted = 0;
        for (Long id : ids) {
            ChatMessage message = id == null ? null : find(id);
            if (message == null || message.isDeleted() || message.getJoi() == null
                    || !message.getJoi().getId().equals(joi.getId())) {
                continue;
            }
            delete(message, false);
            deleted++;
        }
        return deleted;
    }
}
