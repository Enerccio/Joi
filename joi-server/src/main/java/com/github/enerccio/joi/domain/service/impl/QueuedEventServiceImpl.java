package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.QueuedEventRepository;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.List;

public class QueuedEventServiceImpl extends ExtendableServiceImpl<QueuedEvent, QueuedEventRepository> implements QueuedEventService {

    @Override
    @CommonTxReadOnly
    public List<QueuedEvent> findWaiting(Joi joi) throws Exception {
        return getRepository().findWaiting(joi);
    }

    @Override
    @CommonTx
    public QueuedEvent markDelivered(QueuedEvent event, ChatMessage message) throws Exception {
        event.setDeliveredIn(message);
        event.setHandled(true);
        return save(event);
    }
}
