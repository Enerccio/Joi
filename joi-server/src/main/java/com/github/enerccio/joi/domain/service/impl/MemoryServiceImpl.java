package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.Collection;
import java.util.List;

public class MemoryServiceImpl extends ExtendableServiceImpl<Memory, MemoryRepository> implements MemoryService {

    @Override
    @CommonTxReadOnly
    public List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception {
        return getRepository().findByImportance(joi, importance);
    }

    @Override
    @CommonTxReadOnly
    public List<Memory> findAll(Joi joi) throws Exception {
        return getRepository().findAll(joi);
    }

    @Override
    @CommonTx
    public Memory create(Joi joi) throws Exception {
        Memory memory = new Memory();
        memory.setJoi(joi);
        memory.setImportance(MemoryImportance.MEDIUM);
        memory.setApproved(true);
        return save(memory);
    }

    @Override
    @CommonTx
    public Memory update(Joi joi, Memory memory) throws Exception {
        Memory stored = hers(joi, memory.getId());
        if (stored == null) {
            return null;
        }
        stored.setName(memory.getName());
        stored.setImportance(memory.getImportance() == null ? MemoryImportance.MEDIUM : memory.getImportance());
        stored.setKeywords(memory.getKeywords());
        stored.setText(memory.getText());
        return save(stored);
    }

    @Override
    @CommonTx
    public int deleteMemories(Joi joi, Collection<Long> ids) throws Exception {
        int deleted = 0;
        for (Long id : ids) {
            Memory memory = hers(joi, id);
            if (memory != null) {
                delete(memory, false);
                deleted++;
            }
        }
        return deleted;
    }

    /** The memory when it is one of hers and not deleted, else null. */
    private Memory hers(Joi joi, Long id) throws Exception {
        Memory memory = id == null ? null : find(id);
        if (memory == null || memory.isDeleted() || memory.getJoi() == null || !memory.getJoi().getId().equals(joi.getId())) {
            return null;
        }
        return memory;
    }
}
