package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.repository.JoiTaskRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Collection;
import java.util.List;

/** The tasks the assistant started. */
public interface JoiTaskService extends ExtendableService<JoiTask, JoiTaskRepository> {

    /** The tasks in one of the states, oldest first. */
    List<JoiTask> findByStatus(Joi joi, Collection<JoiTaskStatus> statuses) throws Exception;

    /** The tasks waiting to run, in the order they were started. */
    List<JoiTask> findQueued(Joi joi) throws Exception;
}
