package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.CancellationToken;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;

import java.util.List;
import java.util.Map;

/**
 * What a {@link TurnEvent} listener sees of the turn and may change. Each value is there from the event its getter
 * names; before that it is null. A listener runs in the request scope of the caller (its user).
 */
public interface TurnControllerEvent {

    /** The event being handled. */
    TurnEvents getEvent();

    Joi getJoi();

    /** Her chat model for this turn. */
    AI getAi();

    TurnInput getInput();

    /** Today's mood, from {@link TurnEvents#AFTER_TEMPLATE_DATA}. */
    Mood getMood();

    /** The data of her system prompt, from {@link TurnEvents#AFTER_TEMPLATE_DATA}; changes go into the prompt. */
    ChatTemplateData getTemplateData();

    /** The messages for the model, from {@link TurnEvents#AFTER_PREPARE_PAYLOAD}; changes are sent. */
    List<LLMChatMessage> getPayload();

    void setPayload(List<LLMChatMessage> payload);

    /** The functions she may call, from {@link TurnEvents#COLLECT_TOOLS}; never null. */
    FunctionSet getFunctions();

    void setFunctions(FunctionSet functions);

    /** The reply so far: the text of every round. Changes count from {@link TurnEvents#BEFORE_STORE} on. */
    String getReply();

    void setReply(String reply);

    /** The stored turn, from {@link TurnEvents#AFTER_STORE}. */
    ChatMessage getMessage();

    /** The shared values of the turn, see {@link TurnProperties}. */
    Map<String, Object> getProperties();

    @SuppressWarnings("unchecked")
    default <T> T getProperty(String key) {
        return (T) getProperties().get(key);
    }

    /** Cancelled when the turn is stopped; a listener cancels it to stop the turn. */
    CancellationToken getCancellationToken();

    /** Skips the listeners of this event that come after the current one. */
    void terminateEvents();

    @FunctionalInterface
    interface Registration {
        void unregister();
    }

    interface EventChain {

        /** The next listener, or the turn goes on. */
        void next();

        /** The turn goes on without the listeners that come after. */
        void terminate();
    }
}
