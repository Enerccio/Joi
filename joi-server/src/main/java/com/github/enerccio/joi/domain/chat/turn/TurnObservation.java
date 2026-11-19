package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.model.impl.AI;

import java.util.Map;

/** What a {@link TurnObserver} sees of an event. The objects are the turn's own: look, do not modify them. */
public interface TurnObservation {

    TurnEvents getEvent();

    /** The id of the turn, {@link TurnProperties#RUN_ID}. */
    String getRunId();

    Joi getJoi();

    AI getAi();

    TurnInput getInput();

    /** The reply so far. */
    String getReply();

    /** The stored turn, from {@link TurnEvents#AFTER_STORE}; null before. */
    ChatMessage getMessage();

    /** The properties of the turn ({@link TurnProperties}), a view that cannot be changed. */
    Map<String, Object> getProperties();

    @SuppressWarnings("unchecked")
    default <T> T getProperty(String key) {
        return (T) getProperties().get(key);
    }
}
