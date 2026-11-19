package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaBodyRepository extends JpaExtendableRepository<Body> implements BodyRepository {

    @Override
    protected Class<Body> getEntityClass() {
        return Body.class;
    }

    @Override
    public List<Body> findByJoi(Joi joi) throws Exception {
        return entityManager.createQuery("SELECT b FROM Body b WHERE b.deleted = false AND b.joi = :joi ORDER BY b.id ASC", Body.class)
                .setParameter("joi", joi).getResultList();
    }

    @Override
    public List<Body> findByModel(Model model) throws Exception {
        return entityManager.createQuery("SELECT b FROM Body b WHERE b.deleted = false AND (b.model = :model OR b.lookingGlassModel = :model) ORDER BY b.id ASC", Body.class)
                .setParameter("model", model).getResultList();
    }
}
