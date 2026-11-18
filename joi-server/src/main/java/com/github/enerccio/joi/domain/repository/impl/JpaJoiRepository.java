package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.JoiRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

public class JpaJoiRepository extends JpaExtendableRepository<Joi> implements JoiRepository {

    @Override
    protected Class<Joi> getEntityClass() {
        return Joi.class;
    }
}
