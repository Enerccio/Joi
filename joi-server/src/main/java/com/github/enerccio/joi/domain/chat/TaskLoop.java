package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.function.FunctionSet;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The loop of a task of the stronger model: the instruction as the user's message, then rounds of the model and of the functions it
 * calls until it writes an answer without calls, which is the result. The last round has no functions, so it must answer. Used by the
 * web tasks ({@link WebResearch}) and by the tasks of the extensions ({@code ExtensionTask}).
 * <p>
 * Runs on the thread that calls {@link #run}; it waits for the model and for each function, and stops between its steps when it is
 * cancelled.
 */
public final class TaskLoop {

    /** How long the model may take for one round. */
    static final long ROUND_MINUTES = 15;
    /** How long a function may take: the longest page timeout of the options is 300 s. */
    static final long CALL_SECONDS = 330;

    /** What the task writes in its transcript, in the order it happens. */
    public interface Transcript {
        void add(TaskMessageRole role, String content, String reasoning, List<LLMToolCall> calls, String toolCallId, RoundUsage usage);
    }

    private final InferenceService service;
    private final Executor executor;
    private final BooleanSupplier cancelled;
    private final Transcript transcript;

    /** @param cancelled asked between the steps: true stops the task */
    public TaskLoop(InferenceService service, Executor executor, BooleanSupplier cancelled, Transcript transcript) {
        this.service = service;
        this.executor = executor;
        this.cancelled = cancelled;
        this.transcript = transcript;
    }

    /**
     * Runs the task.
     *
     * @param functions what the model may call
     * @param rounds    how many rounds it has, the last one without functions
     * @param shown     after the results of a round: a message with the pictures it asked to see, null for none (or no such
     *                  function); null for a task without pictures
     * @return the answer of the model
     */
    public String run(String instruction, FunctionSet functions, int rounds, Callable<LLMChatMessage> shown) throws Exception {
        List<LLMChatMessage> payload = new ArrayList<>();
        payload.add(LLMChatMessage.of(LLMRole.USER, instruction));
        transcript.add(TaskMessageRole.USER, instruction, null, null, null, null);
        for (int round = 1; round <= rounds; round++) {
            checkCancelled();
            boolean last = round == rounds;
            Round answer = ask(payload, last ? FunctionSet.empty() : functions, last);
            List<LLMToolCall> calls = answer.calls() == null ? List.of() : answer.calls();
            if (calls.isEmpty() || last) {
                String text = StringUtils.strip(StringUtils.defaultString(answer.text()));
                transcript.add(TaskMessageRole.ASSISTANT, text, answer.reasoning(), null, null, answer.usage());
                if (text.isEmpty()) {
                    throw new IllegalStateException("The model gave no answer");
                }
                return text;
            }
            transcript.add(TaskMessageRole.ASSISTANT, answer.text(), answer.reasoning(), calls, null, answer.usage());
            LLMChatMessage calling = LLMChatMessage.ofToolCalls(StringUtils.defaultString(answer.text()), calls);
            if (StringUtils.isNotBlank(answer.reasoning())) {
                calling = calling.withReasoning(answer.reasoning().strip(), true);
            }
            payload.add(calling);
            for (LLMToolCall call : calls) {
                checkCancelled();
                String result = call(functions, call);
                transcript.add(TaskMessageRole.TOOL, result, null, null, call.getId(), null);
                payload.add(LLMChatMessage.ofToolResult(call.getId(), result));
            }
            if (shown != null) {
                // the pictures asked for with view_image: a tool result is text only, so they come in a message of their own
                LLMChatMessage looked = shown.call();
                if (looked != null) {
                    transcript.add(TaskMessageRole.USER, looked.getContent(), null, null, null, null);
                    payload.add(looked);
                }
            }
        }
        throw new IllegalStateException("The model did not finish");
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("The task was cancelled");
        }
    }

    private record Round(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
    }

    private Round ask(List<LLMChatMessage> payload, FunctionSet functions, boolean noCalls) throws Exception {
        CompletableFuture<Round> done = new CompletableFuture<>();
        service.round(payload, null, functions, noCalls, new InferenceRoundCallback() {
            @Override
            public void onChunk(ChunkType chunkType, String chunk) {
            }

            @Override
            public void onRound(String text, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(new Round(text, null, calls, usage));
            }

            @Override
            public void onRound(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(new Round(text, reasoning, calls, usage));
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
        try {
            return done.get(ROUND_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            throw new IllegalStateException("The model did not answer in " + ROUND_MINUTES + " minutes");
        }
    }

    /** A call of the model: what the function answers, or the reason it failed (the model reads either). */
    private String call(FunctionSet functions, LLMToolCall call) throws InterruptedException {
        try {
            return functions.call(call.getName(), StringUtils.defaultIfBlank(call.getArguments(), "{}"), executor, cancelled)
                    .get(CALL_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // the function set already says which function failed and why
            return StringUtils.defaultIfBlank(cause.getMessage(), "Failed: " + cause.getClass().getSimpleName());
        } catch (TimeoutException e) {
            return "Failed: it took too long";
        }
    }
}
