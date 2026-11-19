package com.github.enerccio.joi.domain.chat.turn;

/**
 * A read-only, synchronous watcher of an event of every turn, registered with {@code ChatService.addEventObserver}.
 * Unlike a {@link TurnEvent} it cannot change the turn and costs no hop to another thread, so it is the way to follow
 * the pieces of the reply. It is called on the thread that emits the event (for a piece, the thread of the model's
 * stream), before the listeners; the turn waits for it, so it returns quickly. What it throws is logged and ignored.
 */
@FunctionalInterface
public interface TurnObserver {

    void observe(TurnObservation observation);

}
