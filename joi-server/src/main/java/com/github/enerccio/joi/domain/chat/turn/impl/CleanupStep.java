package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnOutcome;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;

/**
 * The end of every turn: {@link TurnEvents#AFTER_CLEANUP} with how it went, then the caller gets the result (or the
 * failure, or the stop) and the next turn of the assistant may start.
 */
public class CleanupStep extends TurnStepBase {

    @Override
    protected void onStep(TurnController controller) throws Exception {
        boolean partial = Boolean.TRUE.equals(controller.getProperties().get(TurnProperties.PARTIAL));
        TurnOutcome outcome = partial ? TurnOutcome.FAILED
                : controller.getMessage() != null ? TurnOutcome.COMPLETED
                : controller.getFailure() != null ? TurnOutcome.FAILED : TurnOutcome.STOPPED;
        controller.getProperties().put(TurnProperties.OUTCOME, outcome);
        controller.emitEvent(TurnEvents.AFTER_CLEANUP, controller::next);
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.CLEANUP;
    }
}
