package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCrudTest extends ExtendableCrudContract<Memory> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private MemoryService memoryService;

    @Override
    protected OwnedService<Memory, ?> service() {
        return memoryService;
    }

    @Override
    protected Memory newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        Memory memory = new Memory();
        memory.setJoi(joi);
        memory.setImportance(MemoryImportance.IMPORTANT);
        memory.setText("Pat is allergic to peanuts.");
        memory.setKeywords("peanuts, allergy, food");
        return memory;
    }

    @Override
    protected void assertCreated(Memory loaded) {
        assertThat(loaded.getImportance()).isEqualTo(MemoryImportance.IMPORTANT);
        assertThat(loaded.getText()).isEqualTo("Pat is allergic to peanuts.");
        assertThat(loaded.getKeywords()).isEqualTo("peanuts, allergy, food");
        assertThat(loaded.isPrivateMode()).isFalse();
    }

    @Override
    protected void modify(Memory entity) {
        entity.setImportance(MemoryImportance.TRIVIAL);
        entity.setApproved(true);
    }

    @Override
    protected void assertModified(Memory loaded) {
        assertThat(loaded.getImportance()).isEqualTo(MemoryImportance.TRIVIAL);
        assertThat(loaded.getApproved()).isTrue();
        assertThat(loaded.getText()).isEqualTo("Pat is allergic to peanuts.");
    }
}
