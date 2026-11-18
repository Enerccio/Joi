package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.repository.SummaryRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Date;
import java.util.List;

/** The summaries of times of the conversation. */
public interface SummaryService extends ExtendableService<Summary, SummaryRepository> {

    /** The summaries whose time overlaps {@code [from, to)}, by start. */
    List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception;

    /** The summaries a prompt shows of {@code [from, to)}: the overlapping ones no longer one covers ({@link SummaryCoverage}), oldest first. */
    List<Summary> findForPrompt(Joi joi, Date from, Date to) throws Exception;
}
