package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.List;

/** The transcripts of the tasks. */
public interface JoiTaskMessageRepository extends ExtendableRepository<JoiTaskMessage> {

    /** The transcript of the task, in order. */
    List<JoiTaskMessage> findByTask(JoiTask task) throws Exception;
}
