package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.List;

public class MemoryServiceImpl extends ExtendableServiceImpl<Memory, MemoryRepository> implements MemoryService {

    @Override
    @CommonTxReadOnly
    public List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception {
        return getRepository().findByImportance(joi, importance);
    }
}
