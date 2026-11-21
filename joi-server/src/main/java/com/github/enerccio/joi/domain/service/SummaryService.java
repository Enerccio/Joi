package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.repository.SummaryRepository;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Date;
import java.util.List;

/** The summaries of times of the conversation. */
public interface SummaryService extends ExtendableService<Summary, SummaryRepository> {

    /** The summaries whose time overlaps {@code [from, to)}, by start. */
    List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception;

    /** All the summaries of the assistant, oldest first. */
    List<Summary> findAllOf(Joi joi) throws Exception;

    /**
     * Where the lane of the model's summaries is: the queue they wait in and how many of them run at once. A model that takes a
     * lock on a GPU shares its lane with every model on that GPU (the lock's GPU: one lane for the GPU) and the limit is the least
     * of their connections (and of the locks' limits); any other model is a lane of its own for the user, with its connections.
     */
    Lane lane(AI ai) throws Exception;

    /** The queue of the summaries a model's calls wait in, and how many run at once. */
    record Lane(String key, int limit) {
    }

    /** The summaries a prompt shows of {@code [from, to)}: the overlapping ones no longer one covers ({@link SummaryCoverage}), oldest first. */
    List<Summary> findForPrompt(Joi joi, Date from, Date to) throws Exception;
}
