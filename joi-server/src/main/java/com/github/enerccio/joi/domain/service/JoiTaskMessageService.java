package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.repository.JoiTaskMessageRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The transcripts of the tasks. */
public interface JoiTaskMessageService extends ExtendableService<JoiTaskMessage, JoiTaskMessageRepository> {

    /** The transcript of the task, in order. */
    List<JoiTaskMessage> findByTask(JoiTask task) throws Exception;

    /** Adds the message at the end of the task's transcript. */
    JoiTaskMessage append(JoiTask task, JoiTaskMessage message) throws Exception;
}
