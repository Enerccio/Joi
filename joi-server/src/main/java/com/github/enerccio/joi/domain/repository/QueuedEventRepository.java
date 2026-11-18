package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.List;

/** The events that wait for a connected node. */
public interface QueuedEventRepository extends ExtendableRepository<QueuedEvent> {

    /** The events not handled yet, in the order they happened. */
    List<QueuedEvent> findWaiting(Joi joi) throws Exception;
}
