package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCrudTest extends ExtendableCrudContract<Summary> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private SummaryService summaryService;

    @Override
    protected OwnedService<Summary, ?> service() {
        return summaryService;
    }

    @Override
    protected Summary newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        Summary summary = new Summary();
        summary.setJoi(joi);
        summary.setSummaryType(SummaryType.WEEK);
        summary.setCoverFrom(new Date(1_000L));
        summary.setCoverTo(new Date(2_000L));
        summary.setSummary("A week of GPU fights.");
        summary.setReasoning("Days merged.");
        return summary;
    }

    @Override
    protected void assertCreated(Summary loaded) {
        assertThat(loaded.getSummaryType()).isEqualTo(SummaryType.WEEK);
        assertThat(loaded.getCoverFrom()).hasSameTimeAs(new Date(1_000L));
        assertThat(loaded.getCoverTo()).hasSameTimeAs(new Date(2_000L));
        assertThat(loaded.getSummary()).isEqualTo("A week of GPU fights.");
        assertThat(loaded.getReasoning()).isEqualTo("Days merged.");
    }

    @Override
    protected void modify(Summary entity) {
        entity.setSummary("Rewritten.");
        entity.setPrivateMode(true);
    }

    @Override
    protected void assertModified(Summary loaded) {
        assertThat(loaded.getSummary()).isEqualTo("Rewritten.");
        assertThat(loaded.isPrivateMode()).isTrue();
        assertThat(loaded.getReasoning()).isEqualTo("Days merged.");
    }
}
