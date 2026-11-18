package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JoiTaskCrudTest extends ExtendableCrudContract<JoiTask> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private JoiTaskService joiTaskService;

    @Override
    protected OwnedService<JoiTask, ?> service() {
        return joiTaskService;
    }

    @Override
    protected JoiTask newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        JoiTask task = new JoiTask();
        task.setJoi(joi);
        task.setRole(TextLLMType.DEEP_REASONING);
        task.setTitle("Compare GPUs");
        task.setInstructions("Compare the R9700 with the 5090 for local models.");
        task.setToolCallId("call_1");
        return task;
    }

    @Override
    protected void assertCreated(JoiTask loaded) {
        assertThat(loaded.getStatus()).isEqualTo(JoiTaskStatus.QUEUED);
        assertThat(loaded.getRole()).isEqualTo(TextLLMType.DEEP_REASONING);
        assertThat(loaded.getTitle()).isEqualTo("Compare GPUs");
        assertThat(loaded.getInstructions()).isEqualTo("Compare the R9700 with the 5090 for local models.");
        assertThat(loaded.getToolCallId()).isEqualTo("call_1");
    }

    @Override
    protected void modify(JoiTask entity) {
        entity.setStatus(JoiTaskStatus.DONE);
        entity.setResult("The 5090 is faster.");
    }

    @Override
    protected void assertModified(JoiTask loaded) {
        assertThat(loaded.getStatus()).isEqualTo(JoiTaskStatus.DONE);
        assertThat(loaded.getResult()).isEqualTo("The 5090 is faster.");
        assertThat(loaded.getTitle()).isEqualTo("Compare GPUs");
    }
}
