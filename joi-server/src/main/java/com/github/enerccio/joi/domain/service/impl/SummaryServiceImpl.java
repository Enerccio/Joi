package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.repository.SummaryRepository;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.Date;
import java.util.List;

public class SummaryServiceImpl extends ExtendableServiceImpl<Summary, SummaryRepository> implements SummaryService {

    @Override
    @CommonTxReadOnly
    public List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception {
        return getRepository().findOverlapping(joi, from, to);
    }

    @Override
    @CommonTxReadOnly
    public List<Summary> findForPrompt(Joi joi, Date from, Date to) throws Exception {
        return SummaryCoverage.uncovered(getRepository().findOverlapping(joi, from, to));
    }
}
