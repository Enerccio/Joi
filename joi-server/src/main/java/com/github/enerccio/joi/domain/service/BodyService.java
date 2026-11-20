package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
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
     * Whether each toggle of the body's model is shown in the state the body is in ({@code BodyState}); empty when it
     * has no model or the model has no capabilities.
     */
    java.util.Map<String, Boolean> toggles(Body body) throws Exception;

    /**
     * Switches a part of the body's model described apart ({@code DescribedPart}) to one of its states. When she is in
     * the body, has chatted and the part changed, she is told (an event with the state's sentence).
     *
     * @param partId  the part's id ({@code DescribedPart.id})
     * @param stateId the state's id
     * @return the events queued, empty when none
     */
    List<QueuedEvent> switchPart(Body body, String partId, String stateId) throws Exception;

    /**
     * She switches a part of her body herself (the function she is offered): by the names she knows (the part's, the
     * state's: its name for a group, "shown" or "hidden" for a toggle; case does not matter). It is kept on the body like
     * the user's switch, but she is not told of it as an event: what is returned is what she is told in the function's
     * answer (how it looks, what went with it).
     *
     * @throws IllegalArgumentException with words for her when the body has no such part or state
     */
    String changePart(Body body, String partName, String stateName) throws Exception;

    /** The parts she can switch in the body, each with the names of its states; empty for none. */
    List<PartOption> partOptions(Body body) throws Exception;

    /**
     * A part she can switch.
     *
     * @param part   what she calls it
     * @param states the names of its states, as {@link #changePart} takes them
     */
    record PartOption(String part, List<String> states) {
    }

    /**
     * What the user's request to put a part in a state is, as she is told (nothing is changed: she decides on it).
     *
     * @return null when the part is in that state already
     * @throws IllegalArgumentException when the body has no such part or state
     */
    PartRequest partRequest(Body body, String partId, String stateId) throws Exception;

    /**
     * A request to change a part.
     *
     * @param part     what she calls the part
     * @param state    the state asked for: its name for a group, "shown" or "hidden" for a part of one toggle
     * @param group    whether the part is a group of toggles (with named states)
     * @param sentence how it looks in that state, null for not described
     */
    record PartRequest(String part, String state, boolean group, String sentence) {
    }

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

    /**
     * The animations the body plays, by name: all of the user's when none were chosen ({@code BodyAnimations}), else
     * the chosen ones that are still there.
     */
    List<AnimationPack> animations(Body body) throws Exception;

    /** The body's {@code .joia} of its animations, null when it has none. */
    File animationsFile(Body body) throws Exception;

    /**
     * Makes the written {@code .joia} the body's: a resource of the current user linked to the body, in place of the
     * one before (deleted).
     *
     * @param joia        a file in the model service's upload folder; null for none (the one before is deleted)
     * @param fingerprint what it holds ({@code JoiaService}), to tell whether it is current
     * @param hash        the SHA-256 of the file (hex), known from writing it
     * @return the saved body
     */
    Body linkAnimations(Body body, File joia, String fingerprint, String hash) throws Exception;
}
