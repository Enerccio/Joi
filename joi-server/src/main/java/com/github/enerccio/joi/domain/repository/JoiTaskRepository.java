package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.Collection;
import java.util.List;

/** The tasks the assistant started. */
public interface JoiTaskRepository extends ExtendableRepository<JoiTask> {

    /** The tasks in one of the states, oldest first. */
    List<JoiTask> findByStatus(Joi joi, Collection<JoiTaskStatus> statuses) throws Exception;
}
