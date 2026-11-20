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
     * What she writes down herself ({@code remember}): an unapproved memory, the user reviews it in the Memories tab.
     *
     * @param importance null for medium
     */
    Memory remember(Joi joi, String name, String text, String keywords, MemoryImportance importance) throws Exception;

    /**
     * Her memories that fit the words, the best first ({@code recall}): a memory scores by the words of the query found
     * in its keywords, its name and its text, the keywords counting most.
     *
     * @param limit at most this many
     */
    List<Memory> search(Joi joi, String query, int limit) throws Exception;

    /**
     * The memories in her system prompt ({@code {{#memories}}}): the important ones, always, at most {@code limit}, one
     * line each. They do not depend on what was said, so the system prompt is the same from turn to turn (the model's
     * cache of it keeps). Null when there is none.
     */
    String important(Joi joi, int limit) throws Exception;

    /**
     * The memories that fit what was said, for the message ({@code RECALLED_MEMORIES}): the ones with a keyword in the
     * words said, the important ones left out (they are in her system prompt), at most {@code limit}, one line each.
     * Null when there is none.
     */
    String recalled(Joi joi, String said, int limit) throws Exception;

    /**
     * Deletes her memories (soft); an id that isn't one of hers is skipped.
     *
     * @return how many were deleted
     */
    int deleteMemories(Joi joi, Collection<Long> ids) throws Exception;
}
