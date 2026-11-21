package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * One request to a model that answers in text: the prompt goes in as a message of the user, the answer comes back
 * whole. The gate of the model's connections and the GPU lock are taken by the service itself, per call.
 */
public final class ModelRound {

    /** How long the model may take. */
    public static final long MINUTES = 20;

    private ModelRound() {
    }

    /** What the model wrote, its thinking when it did, and the tokens of the request. */
    public record Result(String text, String reasoning, long promptTokens, long completionTokens) {
    }

    /**
     * @param cancelled asked while the model works: true stops the request
     * @throws IllegalStateException when the model gives no text, or takes longer than {@link #MINUTES}
     */
    public static Result complete(InferenceService service, String prompt, BooleanSupplier cancelled) throws Exception {
        CompletableFuture<Result> done = new CompletableFuture<>();
        service.round(List.of(LLMChatMessage.of(LLMRole.USER, prompt)), null, FunctionSet.empty(), true, new InferenceRoundCallback() {
            @Override
            public void onChunk(ChunkType chunkType, String chunk) {
            }

            @Override
            public void onRound(String text, List<LLMToolCall> calls, RoundUsage usage) {
                onRound(text, null, calls, usage);
            }

            @Override
            public void onRound(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(new Result(StringUtils.strip(StringUtils.defaultString(text)), reasoning,
                        usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens()));
            }

            @Override
            public void onCancel() {
                done.completeExceptionally(new CancellationException("The model stopped"));
            }

            @Override
            public void onError(Throwable exception) {
                done.completeExceptionally(exception);
            }

            @Override
            public boolean isDead() {
                return cancelled.getAsBoolean();
            }
        });
        Result result;
        try {
            result = done.get(MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            throw new IllegalStateException("The model did not answer in " + MINUTES + " minutes");
        }
        if (result.text().isEmpty()) {
            throw new IllegalStateException("The model wrote no summary");
        }
        return result;
    }
}
