package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.List;

/** The bodies of the assistants. */
public interface BodyRepository extends ExtendableRepository<Body> {

    /** The bodies of the assistant. */
    List<Body> findByJoi(Joi joi) throws Exception;

    /** The bodies made of the model (as the full one or the Looking Glass one). */
    List<Body> findByModel(Model model) throws Exception;
}
