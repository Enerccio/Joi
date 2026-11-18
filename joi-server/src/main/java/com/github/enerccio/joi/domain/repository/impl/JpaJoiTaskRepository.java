package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.repository.JoiTaskRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.Collection;
import java.util.List;

public class JpaJoiTaskRepository extends JpaExtendableRepository<JoiTask> implements JoiTaskRepository {

    @Override
    protected Class<JoiTask> getEntityClass() {
        return JoiTask.class;
    }

    @Override
    public List<JoiTask> findByStatus(Joi joi, Collection<JoiTaskStatus> statuses) throws Exception {
        return entityManager.createQuery("SELECT t FROM JoiTask t WHERE t.deleted = false AND t.joi = :joi AND t.status IN :statuses ORDER BY t.creation ASC, t.id ASC", JoiTask.class)
                .setParameter("joi", joi).setParameter("statuses", statuses).getResultList();
    }
}
