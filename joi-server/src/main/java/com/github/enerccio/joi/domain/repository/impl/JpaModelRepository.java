package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.repository.ModelRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

public class JpaModelRepository extends JpaExtendableRepository<Model> implements ModelRepository {

    @Override
    protected Class<Model> getEntityClass() {
        return Model.class;
    }
}
