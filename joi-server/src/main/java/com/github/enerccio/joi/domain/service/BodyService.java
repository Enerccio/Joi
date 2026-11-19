package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
import java.util.List;
import java.util.Map;

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

    /** The hash of the file the body shows of its model (its VRM), null when it has none. */
    String modelHash(Body body) throws Exception;

    /**
     * Stores the snapshots the renderer took of the body's model as resources of the user, in place of the ones
     * before, with the hash of the model they are of.
     *
     * @param shots the shot ({@code full-front}) → the JPEG
     */
    Body storeSnapshots(Body body, Map<String, byte[]> shots) throws Exception;

    /**
     * Makes an uploaded image one of the body's references (images the user gave, for the LLM).
     *
     * @param file an uploaded file in the user's folder of resources ({@code ModelService#uploadFolder})
     * @throws IllegalArgumentException when it is not a PNG, JPEG or WebP image (the file is deleted)
     */
    Body addReference(Body body, File file, String fileName) throws Exception;

    /** Removes a reference image. */
    Body removeReference(Body body, String resourceId) throws Exception;

    /** The file of a snapshot or a reference of the body, null when it is not one of the body's. */
    File imageFile(Body body, String resourceId) throws Exception;

    /**
     * Uses the proposal as reviewed: the description becomes the body's (tied to the hash of the snapshots' model)
     * and the toggle names go to the model; the proposal is gone. When she is in this body and has chatted, she is
     * told how she looks now.
     *
     * @param toggleNames the toggle's id → the name, the ones the user kept
     * @return the events queued, empty when none
     */
    List<QueuedEvent> applyProposal(Body body, String description, Map<String, String> toggleNames) throws Exception;

    /** Forgets the proposal. */
    Body discardProposal(Body body) throws Exception;

    /**
     * The section of her prompt about her body ({@code {{#body}}}): its name, how she looks, the expressions and the
     * parts she can show or hide (named by the user), and her other bodies. Null when she is in none.
     */
    String prompt(Joi joi) throws Exception;
}
