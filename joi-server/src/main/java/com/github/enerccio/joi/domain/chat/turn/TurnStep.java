package com.github.enerccio.joi.domain.chat.turn;

/**
 * One step of a chat turn. The steps are beans, listed in the order of {@link TurnStepType} under the chat service
 * in {@code services-config.xml}; each type once.
 */
public interface TurnStep {

    TurnStepType getType();

    void step(TurnController controller) throws Exception;

}
