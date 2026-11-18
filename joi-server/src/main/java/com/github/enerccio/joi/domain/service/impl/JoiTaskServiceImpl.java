package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.repository.JoiTaskRepository;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.Collection;
import java.util.List;

public class JoiTaskServiceImpl extends ExtendableServiceImpl<JoiTask, JoiTaskRepository> implements JoiTaskService {

    @Override
    @CommonTxReadOnly
    public List<JoiTask> findByStatus(Joi joi, Collection<JoiTaskStatus> statuses) throws Exception {
        return getRepository().findByStatus(joi, statuses);
    }

    @Override
    @CommonTxReadOnly
    public List<JoiTask> findQueued(Joi joi) throws Exception {
        return getRepository().findByStatus(joi, List.of(JoiTaskStatus.QUEUED));
    }
}
