package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The bodies of the assistants (see {@code DESIGN.md} → Bodies). */
public interface BodyService extends ExtendableService<Body, BodyRepository> {

    /** The bodies of the assistant, by name. */
    List<Body> findForJoi(Joi joi) throws Exception;

    /** The body she is in, null for none. */
    Body active(Joi joi) throws Exception;

    /** The bodies made of the model (as the full one or the Looking Glass one): a model in use is not deleted. */
    List<Body> findByModel(Model model) throws Exception;

    /**
     * A new body of the assistant with the name. Her first body is the one she is in, and she is told (an event queued)
     * when she has chatted already.
     */
    Body create(Joi joi, String name) throws Exception;

    /**
     * Puts her into the body (null: into none), out of the one she was in. She is told (an event queued) when she has
     * chatted already and the body changed.
     *
     * @return the events queued, empty when none
     */
    List<QueuedEvent> activate(Joi joi, Body body) throws Exception;

    /**
     * Deletes the body. When she was in it she is in none afterwards, and she is told as for {@link #activate}.
     *
     * @return the events queued, empty when none
     */
    List<QueuedEvent> deleteBody(Body body) throws Exception;

    /**
     * How she looks in the body now: its model's description for the state it is in ({@code Appearance}); null when it
     * has no model or the model is not described.
     */
    String appearance(Body body) throws Exception;

    /**
     * The model's description changed: each assistant who is in a body of it and has chatted is told how she looks now.
     *
     * @return the events queued, empty when none
     */
    List<QueuedEvent> modelDescribed(Model model) throws Exception;

    /**
     * The section of her prompt about her body ({@code {{#body}}}, a template): its name, how she looks, the expressions
     * and the parts she can show or hide (named by the user), and her other bodies. Null when she is in none.
     */
    String prompt(Joi joi) throws Exception;
}
