package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskMessageService;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JoiTaskMessageCrudTest extends ExtendableCrudContract<JoiTaskMessage> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private JoiTaskService joiTaskService;

    @Autowired
    private JoiTaskMessageService joiTaskMessageService;

    @Override
    protected OwnedService<JoiTaskMessage, ?> service() {
        return joiTaskMessageService;
    }

    @Override
    protected JoiTaskMessage newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        JoiTask task = new JoiTask();
        task.setJoi(joi);
        task = joiTaskService.save(task);
        JoiTaskMessage message = new JoiTaskMessage();
        message.setTask(task);
        message.setOrdinal(3);
        message.setRole(TaskMessageRole.ASSISTANT);
        message.setContent("Looking it up.");
        message.setReasoning("Search first.");
        message.setToolCalls("[{\"name\": \"search\"}]");
        return message;
    }

    @Override
    protected void assertCreated(JoiTaskMessage loaded) {
        assertThat(loaded.getTask()).isNotNull();
        assertThat(loaded.getOrdinal()).isEqualTo(3);
        assertThat(loaded.getRole()).isEqualTo(TaskMessageRole.ASSISTANT);
        assertThat(loaded.getContent()).isEqualTo("Looking it up.");
        assertThat(loaded.getReasoning()).isEqualTo("Search first.");
        assertThat(loaded.getToolCalls()).isEqualTo("[{\"name\": \"search\"}]");
    }

    @Override
    protected void modify(JoiTaskMessage entity) {
        entity.setRole(TaskMessageRole.TOOL);
        entity.setToolCallId("call_1");
    }

    @Override
    protected void assertModified(JoiTaskMessage loaded) {
        assertThat(loaded.getRole()).isEqualTo(TaskMessageRole.TOOL);
        assertThat(loaded.getToolCallId()).isEqualTo("call_1");
        assertThat(loaded.getContent()).isEqualTo("Looking it up.");
    }
}
