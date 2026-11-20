package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.marginalia.domain.service.CancellationToken;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One round of the model. A round that asks for functions goes to {@link TurnStepType#CALL_TOOLS}, which comes back
 * here; one without calls goes on to {@link TurnStepType#STORE}. After {@link InferenceService#MAX_FUNCTION_ROUNDS}
 * rounds with calls the model is told to answer in text.
 */
public class InferenceStep extends TurnStepBase {

    private static final Logger log = LoggerFactory.getLogger(InferenceStep.class);

    /** How long a round may take before the turn fails. */
    static final long ROUND_TIMEOUT_MINUTES = 15;

    @Autowired
    private InferenceServices inferenceServices;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        controller.emitEvent(TurnEvents.BEFORE_INFERENCE, () -> round(controller));
    }

    private void round(TurnController controller) throws Exception {
        InferenceService service = inferenceServices.forAIOrThrow(controller.getAi());
        int done = rounds(controller);
        boolean noCalls = done >= InferenceService.MAX_FUNCTION_ROUNDS;
        // the round ends once: in an answer, a stop, an error or the time limit
        AtomicBoolean over = new AtomicBoolean();
        // the stop of the turn is listened to while the round runs
        AtomicReference<Runnable> stopListener = new AtomicReference<>();
        Runnable stopped = () -> {
            if (over.compareAndSet(false, true)) {
                controller.wrapCallback(() -> controller.jumpTo(TurnStepType.CLEANUP)).run();
            }
        };
        Consumer<Throwable> failed = exception -> {
            if (over.compareAndSet(false, true)) {
                forget(stopListener);
                controller.fail(exception);
                controller.wrapCallback(() -> controller.jumpTo(TurnStepType.CLEANUP)).run();
            }
        };
        InferenceRoundCallback callback = new InferenceRoundCallback() {
            @Override
            public void onChunk(ChunkType chunkType, String chunk) {
                controller.markFirstToken();
                boolean reasoning = chunkType == ChunkType.REASONING;
                controller.getProperties().put(TurnProperties.CHUNK, chunk);
                controller.emitEvent(reasoning ? TurnEvents.REASONING_CHUNK_RECEIVED : TurnEvents.CHUNK_RECEIVED, null);
                try {
                    if (reasoning) {
                        controller.getListener().onReasoning(chunk);
                    } else {
                        controller.getListener().onText(chunk);
                    }
                } catch (Exception e) {
                    log.debug("A listener of a chat failed: {}", e.getMessage());
                }
            }

            @Override
            public void onRound(String text, List<LLMToolCall> calls, RoundUsage usage) {
                onRound(text, null, calls, usage);
            }

            @Override
            public void onRound(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
                if (over.compareAndSet(false, true)) {
                    forget(stopListener);
                    controller.wrapCallback(() -> answered(controller, noCalls, text, reasoning, calls, usage)).run();
                }
            }

            @Override
            public void onCancel() {
                stopped.run();
            }

            @Override
            public void onError(Throwable exception) {
                failed.accept(exception);
            }

            @Override
            public boolean isDead() {
                return controller.getCancellationToken().isCancelled();
            }
        };
        CancellationToken request = service.round(controller.getPayload(), null, controller.getFunctions(), noCalls, callback);
        // a stop reaches the request (which may be waiting for the server to read the prompt) and ends the round
        Runnable unregister = controller.getCancellationToken().onCancel(() -> {
            if (request != null) {
                request.cancel();
            }
            stopped.run();
        });
        stopListener.set(unregister);
        if (over.get()) {
            unregister.run();
        }
        CompletableFuture.delayedExecutor(ROUND_TIMEOUT_MINUTES, TimeUnit.MINUTES, controller.getExecutor()).execute(() -> {
            if (!over.get()) {
                failed.accept(new TimeoutException("The model did not answer in " + ROUND_TIMEOUT_MINUTES + " minutes"));
                if (request != null) {
                    request.cancel();
                }
            }
        });
    }

    private void answered(TurnController controller, boolean noCalls, String text, String reasoning, List<LLMToolCall> calls,
                          RoundUsage usage) {
        controller.addUsage(usage);
        // a round that used its whole budget and gave neither words nor calls is a failure, not an empty answer to store: a model
        // that starts thinking and never stops (the server may hold such output back, so nothing of it arrived) would leave
        // her silent and the user with a blank message
        Integer limit = controller.getAi().getMaxCompletionTokens();
        if (limit != null && limit > 0 && usage != null && usage.completionTokens() >= limit * 0.98
                && StringUtils.isBlank(text) && (calls == null || calls.isEmpty())) {
            // what is known of the round goes with it: it is rare, and nothing else tells where the tokens went
            InferenceService.RoundStats stats = usage.stats();
            String known = stats == null ? "" : " [finish " + stats.finishReason() + ", reasoning tokens " + stats.reasoningTokens()
                    + ", prompt " + usage.promptTokens() + " of which cached " + stats.cachedTokens() + ", first piece after "
                    + stats.firstTokenMillis() + " ms, server wrote for " + stats.serverPredictedMillis() + " ms, thinking received "
                    + StringUtils.length(reasoning) + " chars]";
            log.warn("A round of the model used its whole budget of {} tokens and gave no answer{}", limit, known);
            controller.fail(new IllegalStateException("The model used its whole budget of " + limit + " tokens without answering"
                    + (StringUtils.isBlank(reasoning) ? "" : " (it only thought)") + known));
            controller.wrapCallback(() -> controller.jumpTo(TurnStepType.CLEANUP)).run();
            return;
        }
        int rounds = rounds(controller) + 1;
        String roundText = StringUtils.strip(StringUtils.defaultString(text));
        controller.getProperties().put(TurnProperties.ROUNDS, rounds);
        controller.getProperties().put(TurnProperties.ROUND_TEXT, roundText);
        controller.getProperties().put(TurnProperties.ROUND_CALLS, calls == null ? List.of() : List.copyOf(calls));
        controller.setReply(join(controller.getReply(), roundText));
        if (StringUtils.isNotBlank(reasoning)) {
            controller.setReasoning(join(controller.getReasoning(), reasoning.strip()));
        }
        controller.emitEvent(TurnEvents.AFTER_INFERENCE, () -> {
            List<LLMToolCall> asked = controller.getProperties().get(TurnProperties.ROUND_CALLS) instanceof List<?> list
                    ? list.stream().map(LLMToolCall.class::cast).toList() : List.of();
            if (!noCalls && !asked.isEmpty() && !controller.getFunctions().isEmpty()) {
                // what the model thought goes with its calls, as the platform's own tool loop does it: a model set to get it
                // back between the calls does (ReasoningPassthrough, the Gemma 4 way), the others never see it
                LLMChatMessage calling = LLMChatMessage.ofToolCalls(roundText, asked);
                if (StringUtils.isNotBlank(reasoning)) {
                    calling = calling.withReasoning(reasoning.strip(), true);
                }
                controller.getPayload().add(calling);
                controller.next();
                return;
            }
            controller.markTextDone();
            controller.jumpTo(TurnStepType.STORE);
        });
    }

    private static void forget(AtomicReference<Runnable> stopListener) {
        Runnable unregister = stopListener.getAndSet(null);
        if (unregister != null) {
            unregister.run();
        }
    }

    private static int rounds(TurnController controller) {
        return controller.getProperties().get(TurnProperties.ROUNDS) instanceof Integer n ? n : 0;
    }

    private static String join(String before, String more) {
        if (StringUtils.isBlank(before)) {
            return StringUtils.defaultString(more);
        }
        return StringUtils.isBlank(more) ? before : before + "\n\n" + more;
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.INFERENCE;
    }
}
