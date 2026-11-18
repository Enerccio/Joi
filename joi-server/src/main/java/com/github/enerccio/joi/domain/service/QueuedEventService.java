package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.QueuedEventRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The events that wait for a connected node. */
public interface QueuedEventService extends ExtendableService<QueuedEvent, QueuedEventRepository> {

    /** The events not handled yet, in the order they happened. */
    List<QueuedEvent> findWaiting(Joi joi) throws Exception;

    /** Marks the event as delivered in the message (null: dropped, it no longer matters). */
    QueuedEvent markDelivered(QueuedEvent event, ChatMessage message) throws Exception;
}
