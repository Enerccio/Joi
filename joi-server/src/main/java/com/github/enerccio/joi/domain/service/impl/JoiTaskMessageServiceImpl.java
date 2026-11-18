package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.repository.JoiTaskMessageRepository;
import com.github.enerccio.joi.domain.service.JoiTaskMessageService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import java.util.List;

public class JoiTaskMessageServiceImpl extends ExtendableServiceImpl<JoiTaskMessage, JoiTaskMessageRepository> implements JoiTaskMessageService {

    @Override
    @CommonTxReadOnly
    public List<JoiTaskMessage> findByTask(JoiTask task) throws Exception {
        return getRepository().findByTask(task);
    }

    @Override
    @CommonTx
    public JoiTaskMessage append(JoiTask task, JoiTaskMessage message) throws Exception {
        List<JoiTaskMessage> transcript = getRepository().findByTask(task);
        message.setTask(task);
        message.setOrdinal(transcript.isEmpty() ? 0 : transcript.getLast().getOrdinal() + 1);
        return save(message);
    }
}
