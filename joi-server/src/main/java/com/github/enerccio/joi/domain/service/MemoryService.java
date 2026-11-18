package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The memories of the assistants. */
public interface MemoryService extends ExtendableService<Memory, MemoryRepository> {

    /** The memories of the importance. */
    List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception;
}
