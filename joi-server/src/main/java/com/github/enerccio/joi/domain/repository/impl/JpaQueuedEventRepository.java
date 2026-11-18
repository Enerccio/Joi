package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.QueuedEventRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaQueuedEventRepository extends JpaExtendableRepository<QueuedEvent> implements QueuedEventRepository {

    @Override
    protected Class<QueuedEvent> getEntityClass() {
        return QueuedEvent.class;
    }

    @Override
    public List<QueuedEvent> findWaiting(Joi joi) throws Exception {
        return entityManager.createQuery("SELECT e FROM QueuedEvent e WHERE e.deleted = false AND e.joi = :joi AND e.handled = false ORDER BY e.occurredAt ASC, e.id ASC", QueuedEvent.class)
                .setParameter("joi", joi).getResultList();
    }
}
