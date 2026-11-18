package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.MoodSetting;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoiCrudTest extends ExtendableCrudContract<Joi> {

    @Autowired
    private JoiService joiService;

    @Override
    protected OwnedService<Joi, ?> service() {
        return joiService;
    }

    @Override
    protected Joi newEntity() throws Exception {
        Joi joi = new Joi();
        joi.setAssistantName("Kairo");
        joi.setUserName("Pat");
        joi.setWakeWord("Hey Kairo");
        joi.setPersonality("tsundere");
        joi.setMasterTemplate("You are {{char}}, talking to {{user}}.");
        joi.setPauseMinutes(45);
        MoodSetting mood = new MoodSetting();
        mood.setKey("grumpy");
        joi.setMoods(List.of(mood));
        joi.setMood("grumpy");
        return joi;
    }

    @Override
    protected void assertCreated(Joi loaded) {
        assertThat(loaded.getAssistantName()).isEqualTo("Kairo");
        assertThat(loaded.getUserName()).isEqualTo("Pat");
        assertThat(loaded.getWakeWord()).isEqualTo("Hey Kairo");
        assertThat(loaded.getPersonality()).isEqualTo("tsundere");
        assertThat(loaded.getMasterTemplate()).isEqualTo("You are {{char}}, talking to {{user}}.");
        assertThat(loaded.getPauseMinutes()).isEqualTo(45);
        assertThat(loaded.getMoods()).extracting(MoodSetting::getKey).containsExactly("grumpy");
        assertThat(loaded.getMood()).isEqualTo("grumpy");
    }

    @Override
    protected void modify(Joi entity) {
        entity.setAssistantName("Joi");
        entity.setUserName(null);
    }

    @Override
    protected void assertModified(Joi loaded) {
        assertThat(loaded.getAssistantName()).isEqualTo("Joi");
        assertThat(loaded.getUserName()).isNull();
        assertThat(loaded.getWakeWord()).isEqualTo("Hey Kairo");
    }
}
