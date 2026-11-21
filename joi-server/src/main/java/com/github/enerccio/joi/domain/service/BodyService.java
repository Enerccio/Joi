package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.Outfit;
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
     * Tells her nodes that the body changed in a way the service did not see (its model or animations chosen in a dialog): they
     * read it again. Nothing when the body is not the one she is in.
     */
    void changed(Body body) throws Exception;

    /** Tells the nodes of the assistants that are in a body of this model that its file was written again. */
    void modelChanged(Model model) throws Exception;

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

    // ----------------------------------------------------------------------------------------------------------------
    // outfits: presets of the body's toggles, with a description
    // ----------------------------------------------------------------------------------------------------------------

    /** The body's outfits, in the order they were made. */
    List<Outfit> outfits(Body body) throws Exception;

    /** The outfit the body's toggles are in the state of now, null when they are in the state of none. */
    Outfit currentOutfit(Body body) throws Exception;

    /**
     * Adds an outfit to the body.
     *
     * @param toggles the toggle's id → whether it is shown; null for the state the body is in now
     * @return the outfit as stored
     * @throws IllegalArgumentException when the name is blank or the body has an outfit of that name (case aside)
     */
    Outfit addOutfit(Body body, String name, String description, java.util.Map<String, Boolean> toggles) throws Exception;

    /**
     * Stores an outfit changed (by its id): its name, description and toggles.
     *
     * @throws IllegalArgumentException when the body has no such outfit, or another has the new name
     */
    Outfit saveOutfit(Body body, Outfit outfit) throws Exception;

    /** Removes the outfit (by its id) from the body; what she wears stays as it is. */
    void deleteOutfit(Body body, String outfitId) throws Exception;

    /**
     * The user puts the outfit on the body: its toggles are set. When she is in the body, has chatted and it changed,
     * she is told (an event with how she looks now).
     *
     * @return the events queued, empty when none
     * @throws IllegalArgumentException when the body has no such outfit
     */
    List<QueuedEvent> wear(Body body, String outfitId) throws Exception;

    /**
     * She puts an outfit on herself (the function she is offered), by its name (case aside). It is kept like the
     * user's change, but she is not told of it as an event: what is returned is what she is told in the answer.
     *
     * @throws IllegalArgumentException with words for her when the body has no such outfit
     */
    String wearOutfit(Body body, String outfitName) throws Exception;

    /**
     * What this body is, in the words of her list: the user's description, else the model's (null when neither).
     */
    String description(Body body) throws Exception;

    /**
     * She is told what became hers since the user last edited: a new body (not the one she is in), and new outfits.
     * Called when the user is done editing. All of it in one event, and what is new while one waits is added to that
     * one; nothing is queued before she has chatted.
     *
     * @return the events queued or added to, empty when nothing is new
     */
    List<QueuedEvent> announce(Body body) throws Exception;

    // ----------------------------------------------------------------------------------------------------------------
    // her list of bodies and what she does with it
    // ----------------------------------------------------------------------------------------------------------------

    /** A body in her list: what she reads of it. */
    record BodyInfo(String name, String description, boolean active, List<OutfitInfo> outfits) {
    }

    /** An outfit in her list; {@code worn} when the body she is in has its toggles in that state. */
    record OutfitInfo(String name, String description, boolean worn) {
    }

    /** Her bodies by name, each with its description and outfits. */
    List<BodyInfo> bodyInfos(Joi joi) throws Exception;

    /**
     * She moves into another of her bodies (the function she is offered), by its name (case aside). It is kept like the
     * user's switch, but she is not told of it as an event: what is returned is what she is told in the answer.
     *
     * @throws IllegalArgumentException with words for her when she has no such body
     */
    String switchBody(Joi joi, String bodyName) throws Exception;
}
