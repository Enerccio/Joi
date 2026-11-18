package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.List;

/** The memories of the assistants. */
public interface MemoryRepository extends ExtendableRepository<Memory> {

    /** The memories of the importance. */
    List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception;
}
