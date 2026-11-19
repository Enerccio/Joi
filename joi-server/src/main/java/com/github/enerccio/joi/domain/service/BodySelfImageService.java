package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.model.impl.AI;

/**
 * Her self-image (see {@code DESIGN.md} → Bodies): a multimodal model looks at a body's snapshots and the user's
 * images and notes, and proposes the appearance description and the names of the toggles
 * ({@code BodyProposal}, stored on the body to review). One request at a time, in the background.
 */
public interface BodySelfImageService {

    /** Hears of the end of a request (in the request scope of the user; done or failed, the body says). */
    @FunctionalInterface
    interface Listener {
        void finished(Body body) throws Exception;
    }

    /**
     * The model that looks: her deep reasoning model (the heavy role) when it takes images, else her chat model when it
     * does; null when neither does.
     */
    AI model(Joi joi) throws Exception;

    /**
     * Asks for a proposal in the background; the body's proposal status is RUNNING until it ends.
     *
     * @throws IllegalStateException when she has no model that takes images, the body has no snapshots nor images,
     *                               or a request for it runs already
     */
    void propose(Body body, Listener listener) throws Exception;

    /** Whether a request for the body runs. */
    boolean isRunning(Body body);

    /** Listens to the running request of the body; returns what stops listening. Nothing when none runs. */
    Runnable listen(Body body, Listener listener);
}
