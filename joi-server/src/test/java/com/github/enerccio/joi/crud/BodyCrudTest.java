package com.github.enerccio.joi.crud;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.crud.ExtendableCrudContract;
import com.github.enerccio.marginalia.domain.service.OwnedService;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class BodyCrudTest extends ExtendableCrudContract<Body> {

    @Autowired
    private JoiService joiService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private BodyService bodyService;

    private Model model;

    @Override
    protected OwnedService<Body, ?> service() {
        return bodyService;
    }

    @Override
    protected Body newEntity() throws Exception {
        Joi joi = joiService.save(new Joi());
        Model full = new Model();
        full.setName("Ryuu");
        model = modelService.save(full);
        Body body = new Body();
        body.setJoi(joi);
        body.setModel(model);
        body.setName("Street");
        return body;
    }

    @Override
    protected void assertCreated(Body loaded) {
        assertThat(loaded.getName()).isEqualTo("Street");
        assertThat(loaded.getModel().getId()).isEqualTo(model.getId());
        assertThat(loaded.getLookingGlassModel()).isNull();
        assertThat(loaded.isActive()).isFalse();
    }

    @Override
    protected void modify(Body entity) {
        entity.setActive(true);
        entity.setLookingGlassModel(model);
        entity.setName("Armor");
    }

    @Override
    protected void assertModified(Body loaded) {
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getLookingGlassModel().getId()).isEqualTo(model.getId());
        assertThat(loaded.getName()).isEqualTo("Armor");
    }
}
