package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.Date;
import java.util.List;

/** The summaries of times of the conversation. */
public interface SummaryRepository extends ExtendableRepository<Summary> {

    /** The summaries whose time overlaps {@code [from, to)}, by start. */
    List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception;
}
