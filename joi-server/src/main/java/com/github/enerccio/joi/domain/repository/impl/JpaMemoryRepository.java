package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaMemoryRepository extends JpaExtendableRepository<Memory> implements MemoryRepository {

    @Override
    protected Class<Memory> getEntityClass() {
        return Memory.class;
    }

    @Override
    public List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception {
        return entityManager.createQuery("SELECT m FROM Memory m WHERE m.deleted = false AND m.joi = :joi AND m.importance = :importance ORDER BY m.id ASC", Memory.class)
                .setParameter("joi", joi).setParameter("importance", importance).getResultList();
    }
}
