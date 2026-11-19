package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Collection;
import java.util.List;

/** The memories of the assistants. */
public interface MemoryService extends ExtendableService<Memory, MemoryRepository> {

    /** The memories of the importance. */
    List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception;

    /** All her memories, the newest first. */
    List<Memory> findAll(Joi joi) throws Exception;

    /** A memory the user writes for her: approved, of medium importance, empty until filled in. */
    Memory create(Joi joi) throws Exception;

    /**
     * Stores what the user changed on one of her memories: its name, importance, keywords and text.
     *
     * @return the memory as stored, null when it isn't hers (or is gone)
     */
    Memory update(Joi joi, Memory memory) throws Exception;

    /**
     * Deletes her memories (soft); an id that isn't one of hers is skipped.
     *
     * @return how many were deleted
     */
    int deleteMemories(Joi joi, Collection<Long> ids) throws Exception;
}
