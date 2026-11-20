package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.CancellationToken;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import org.springframework.web.context.request.RequestAttributes;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A running turn as its {@link TurnStep}s see it: the state, the way to the next step and the events. Nothing waits:
 * a step that has to wait for something (a listener, the model, a function, the speech) returns and goes on from a
 * callback, with {@link #next()}, {@link #jumpTo} or the continuation of {@link #emitEvent}. Each hop runs on the
 * turn's executor in the request scope of the caller.
 */
public interface TurnController {

    String getRunId();

    Map<String, Object> getProperties();

    TurnInput getInput();

    Joi getJoi();

    void setJoi(Joi joi);

    AI getAi();

    Mood getMood();

    void setMood(Mood mood);

    ChatTemplateData getTemplateData();

    void setTemplateData(ChatTemplateData templateData);

    List<LLMChatMessage> getPayload();

    void setPayload(List<LLMChatMessage> payload);

    FunctionSet getFunctions();

    void setFunctions(FunctionSet functions);

    /** The text of every round so far. */
    String getReply();

    void setReply(String reply);

    /** The thinking of every round so far, null for none. */
    String getReasoning();

    void setReasoning(String reasoning);

    /** The tools she used so far in this turn, in order; the turn's own list (add to it as calls are answered). */
    java.util.List<com.github.enerccio.joi.domain.chat.ToolUsage> getToolUsages();

    ChatMessage getMessage();

    void setMessage(ChatMessage message);

    /** When the turn began. */
    Date getSent();

    /** Milliseconds from the start to the first piece of the model, -1 before it. */
    long getFirstTokenMs();

    /** Notes the first piece of the model (only the first call counts). */
    void markFirstToken();

    void addUsage(RoundUsage usage);

    long getPromptTokens();

    long getCompletionTokens();

    /** Milliseconds from the start until the text was whole, -1 before. */
    long getTextMs();

    void markTextDone();

    byte[] getAudio();

    void setAudio(byte[] audio);

    String getSpeechError();

    void setSpeechError(String speechError);

    long getSpeechMs();

    void setSpeechMs(long speechMs);

    /** The caller's listener: what it is told while the turn runs. */
    ChatService.Listener getListener();

    CancellationToken getCancellationToken();

    RequestAttributes getRequestAttributes();

    /** Runs work of the turn on its threads, in the caller's request scope (the functions she calls run here). */
    Executor getExecutor();

    /** Fails the turn: the first failure is the one the caller gets. The step then jumps to the cleanup. */
    void fail(Throwable failure);

    Throwable getFailure();

    /** The next step. */
    void next() throws Exception;

    /** Another step. */
    void jumpTo(TurnStepType stepType) throws Exception;

    /**
     * Emits the event: the observers see it at once, then the listeners run one after another, then the continuation
     * runs. An observed-only event ({@link TurnEvents#isObservedOnly()}) runs no listeners and needs no continuation.
     */
    void emitEvent(TurnEvents event, FromEventCallback continueAfterEventHandling);

    /** A callback that goes on with the turn on its executor (from the thread of the model or of a function). */
    Runnable wrapCallback(FromEventCallback callback);

    interface FromEventCallback {

        void returnFromEvent() throws Exception;

    }
}
