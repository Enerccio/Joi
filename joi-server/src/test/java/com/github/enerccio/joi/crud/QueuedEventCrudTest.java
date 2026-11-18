package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class QueuedEventCrudTest extends ExtendableCrudContract<QueuedEvent> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private QueuedEventService queuedEventService;

    @Override
    protected OwnedService<QueuedEvent, ?> service() {
        return queuedEventService;
    }

    @Override
    protected QueuedEvent newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        QueuedEvent event = new QueuedEvent();
        event.setJoi(joi);
        event.setKind("model.added");
        event.setOccurredAt(new Date(5_000L));
        event.setText("The user added the model Gemma 4 31B.");
        event.setPayload("{\"model\": \"gemma-4-31b\"}");
        return event;
    }

    @Override
    protected void assertCreated(QueuedEvent loaded) {
        assertThat(loaded.getKind()).isEqualTo("model.added");
        assertThat(loaded.getOccurredAt()).hasSameTimeAs(new Date(5_000L));
        assertThat(loaded.getText()).isEqualTo("The user added the model Gemma 4 31B.");
        assertThat(loaded.getPayload()).isEqualTo("{\"model\": \"gemma-4-31b\"}");
        assertThat(loaded.isHandled()).isFalse();
    }

    @Override
    protected void modify(QueuedEvent entity) {
        entity.setHandled(true);
    }

    @Override
    protected void assertModified(QueuedEvent loaded) {
        assertThat(loaded.isHandled()).isTrue();
        assertThat(loaded.getText()).isEqualTo("The user added the model Gemma 4 31B.");
    }
}
