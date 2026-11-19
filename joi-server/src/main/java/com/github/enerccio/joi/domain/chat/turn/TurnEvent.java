package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.EventChain;

/**
 * A listener of an event of every turn, registered with {@code ChatService.addEventListener}. It may change what the
 * event offers, and it holds the turn until it calls {@code chain.next()}, from any thread and as late as it likes (a
 * node that asks the user for an approval answers later): a listener that never calls it stops the turn for good. To
 * stop the turn it cancels first ({@code getCancellationToken().cancel()}, then {@code chain.next()}). What it throws
 * is logged and the chain goes on.
 */
@FunctionalInterface
public interface TurnEvent {

    void onEvent(TurnControllerEvent event, EventChain chain) throws Exception;

}
