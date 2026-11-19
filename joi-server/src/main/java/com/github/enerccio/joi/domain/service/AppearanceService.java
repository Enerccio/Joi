package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.AppearanceProposal;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.marginalia.domain.model.impl.AI;

import java.util.List;
import java.util.Map;

/**
 * How she looks in a model (see {@code DESIGN.md} → Bodies, self-image): a multimodal model looks at the model's
 * snapshots and the user's images and notes, and proposes the description of what never changes, the names of the
 * toggles, and for each toggle described apart a sentence for shown and one for hidden, from its two close-ups
 * ({@link AppearanceProposal}, stored on the model to review). One request at a time, in the background.
 */
public interface AppearanceService {

    /** Hears of a running request (in the request scope of the user). */
    interface Listener {

        /** A step of it is done ({@link Model#getProposalProgress()}). */
        default void progress(Model model) throws Exception {
        }

        /** It ended, done or failed (the model says). */
        void finished(Model model) throws Exception;
    }

    /**
     * The model that looks: the user's deep reasoning model (the heavy role) when it takes images, else their chat model
     * when it does; null when neither does.
     */
    AI model() throws Exception;

    /**
     * Asks for a proposal in the background; the model's proposal status is RUNNING until it ends.
     *
     * @throws IllegalStateException when the user has no model that takes images, the model has no snapshots nor images,
     *                               or a request for it runs already
     */
    void propose(Model model, Listener listener) throws Exception;

    /** Whether a request for the model runs. */
    boolean isRunning(Model model);

    /** Listens to the running request of the model; returns what stops listening. Nothing when none runs. */
    Runnable listen(Model model, Listener listener);

    /**
     * Uses the proposal as reviewed: the description and the sentences of the parts become the model's (tied to the hash
     * of the snapshots' VRM), the names too; the proposal is gone. Each assistant who is in a body of it and has
     * chatted is told how she looks now.
     *
     * @param toggleNames the toggle's id → the name, the ones the user kept
     * @param parts       the toggle's id → its sentences, the ones the user kept
     * @return the events queued, empty when none
     */
    List<QueuedEvent> apply(Model model, String description, Map<String, String> toggleNames,
                            Map<String, AppearanceProposal.Part> parts) throws Exception;

    /** Forgets the proposal; returns the saved model. */
    Model discard(Model model) throws Exception;
}
