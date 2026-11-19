package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.util.Iterator;

/**
 * The common part of the steps: each runs in the caller's request scope, a stopped turn goes to the cleanup, and what
 * a step throws fails the turn.
 */
public abstract class TurnStepBase implements TurnStep {

    private static final Logger log = LoggerFactory.getLogger(TurnStepBase.class);

    // the steps belong to the chat service, which is being created when they are
    @Autowired
    @Lazy
    protected ChatService chatService;

    protected abstract void onStep(TurnController controller) throws Exception;

    @Override
    public void step(TurnController controller) throws Exception {
        try (InRequestScope _ = new InRequestScope(controller.getRequestAttributes())) {
            if (controller.getCancellationToken().isCancelled() && getType() != TurnStepType.CLEANUP) {
                controller.jumpTo(TurnStepType.CLEANUP);
                return;
            }
            try {
                onStep(controller);
            } catch (Exception e) {
                if (getType() == TurnStepType.CLEANUP) {
                    throw e;
                }
                log.debug("The step {} of a turn failed", getType(), e);
                controller.fail(e);
                controller.jumpTo(TurnStepType.CLEANUP);
            }
        }
    }

    /** Goes through the items one at a time, each going on when it calls its {@code next}; then {@code onComplete}. */
    protected <T> void forEachAsync(TurnController controller, Iterable<T> items, AsyncItemProcessor<T> processor,
                                    TurnController.FromEventCallback onComplete) throws Exception {
        processNext(controller, items.iterator(), processor, onComplete);
    }

    private <T> void processNext(TurnController controller, Iterator<T> iterator, AsyncItemProcessor<T> processor,
                                 TurnController.FromEventCallback onComplete) throws Exception {
        if (controller.getCancellationToken().isCancelled()) {
            controller.jumpTo(TurnStepType.CLEANUP);
            return;
        }
        if (!iterator.hasNext()) {
            onComplete.returnFromEvent();
            return;
        }
        processor.process(iterator.next(), () -> processNext(controller, iterator, processor, onComplete));
    }

    @FunctionalInterface
    public interface AsyncItemProcessor<T> {
        void process(T item, TurnController.FromEventCallback next) throws Exception;
    }
}
