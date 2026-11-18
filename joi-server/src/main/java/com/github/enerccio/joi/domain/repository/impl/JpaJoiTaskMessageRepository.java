package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.repository.JoiTaskMessageRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaJoiTaskMessageRepository extends JpaExtendableRepository<JoiTaskMessage> implements JoiTaskMessageRepository {

    @Override
    protected Class<JoiTaskMessage> getEntityClass() {
        return JoiTaskMessage.class;
    }

    @Override
    public List<JoiTaskMessage> findByTask(JoiTask task) throws Exception {
        return entityManager.createQuery("SELECT m FROM JoiTaskMessage m WHERE m.deleted = false AND m.task = :task ORDER BY m.ordinal ASC, m.id ASC", JoiTaskMessage.class)
                .setParameter("task", task).getResultList();
    }
}
