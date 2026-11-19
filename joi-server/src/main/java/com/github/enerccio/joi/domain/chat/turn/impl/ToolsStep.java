package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;

/**
 * The functions she may call: none of her own yet, the listeners of {@link TurnEvents#COLLECT_TOOLS} add theirs (the
 * server's tools, the tools of the nodes).
 */
public class ToolsStep extends TurnStepBase {

    @Override
    protected void onStep(TurnController controller) throws Exception {
        controller.setFunctions(FunctionSet.empty());
        controller.emitEvent(TurnEvents.COLLECT_TOOLS, controller::next);
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.TOOLS;
    }
}
