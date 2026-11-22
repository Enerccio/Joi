package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ToolEffects;
import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * The functions the model asked for, one after another, each between {@link TurnEvents#BEFORE_TOOL_CALL} and
 * {@link TurnEvents#AFTER_TOOL_CALL}; the results go into the payload and the model gets another round. A function
 * answers when it likes (a node's tool, a question to the user): nothing waits on a thread meanwhile.
 */
public class CallToolsStep extends TurnStepBase {

    private static final Logger log = LoggerFactory.getLogger(CallToolsStep.class);

    @Override
    protected void onStep(TurnController controller) throws Exception {
        List<LLMToolCall> calls = controller.getProperties().get(TurnProperties.ROUND_CALLS) instanceof List<?> list
                ? list.stream().map(LLMToolCall.class::cast).toList() : List.of();
        forEachAsync(controller, calls, (call, next) -> call(controller, call, next), () -> afterCalls(controller, calls));
    }

    /**
     * The model reads the results in another round, except when it has said its words in this one and only gestured (an animation, a mood, a
     * notice): that round has nothing to add, and a small model fills it with nothing until its budget is gone, so the turn ends here.
     */
    private void afterCalls(TurnController controller, List<LLMToolCall> calls) throws Exception {
        List<ToolUsage> usages = controller.getToolUsages();
        boolean said = StringUtils.isNotBlank((String) controller.getProperties().get(TurnProperties.ROUND_TEXT));
        boolean gestures = !calls.isEmpty() && calls.stream().allMatch(call -> ToolEffects.needsNoAnswer(call.getName()));
        boolean worked = usages.size() >= calls.size()
                && usages.subList(usages.size() - calls.size(), usages.size()).stream().noneMatch(ToolUsage::failed);
        if (said && gestures && worked) {
            controller.markTextDone();
            controller.jumpTo(TurnStepType.STORE);
            return;
        }
        controller.jumpTo(TurnStepType.INFERENCE);
    }

    private void call(TurnController controller, LLMToolCall call, TurnController.FromEventCallback next) {
        Map<String, Object> properties = controller.getProperties();
        properties.put(TurnProperties.TOOL_CALL, call);
        properties.remove(TurnProperties.TOOL_RESULT);
        properties.remove(TurnProperties.TOOL_FAILED);
        controller.emitEvent(TurnEvents.BEFORE_TOOL_CALL, () -> {
            if (properties.containsKey(TurnProperties.TOOL_RESULT)) {
                // a listener answered instead of the function
                answered(controller, call, next);
                return;
            }
            try {
                controller.getListener().onToolStart(call.getName(), call.getArguments());
            } catch (Exception e) {
                log.debug("A listener of a chat failed: {}", e.getMessage());
            }
            controller.getFunctions()
                    .call(call.getName(), StringUtils.defaultIfBlank(call.getArguments(), "{}"), controller.getExecutor(),
                            controller.getCancellationToken()::isCancelled)
                    .whenComplete((result, error) -> controller.wrapCallback(() -> {
                        if (error != null) {
                            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                            log.debug("The function {} failed: {}", call.getName(), cause.getMessage());
                            properties.put(TurnProperties.TOOL_RESULT, StringUtils.defaultIfBlank(cause.getMessage(), cause.toString()));
                            properties.put(TurnProperties.TOOL_FAILED, true);
                        } else {
                            properties.put(TurnProperties.TOOL_RESULT, result);
                            properties.put(TurnProperties.TOOL_FAILED, false);
                        }
                        answered(controller, call, next);
                    }).run());
        });
    }

    private void answered(TurnController controller, LLMToolCall call, TurnController.FromEventCallback next) {
        controller.emitEvent(TurnEvents.AFTER_TOOL_CALL, () -> {
            Map<String, Object> properties = controller.getProperties();
            String result = StringUtils.defaultIfBlank((String) properties.get(TurnProperties.TOOL_RESULT), "OK");
            boolean failed = Boolean.TRUE.equals(properties.get(TurnProperties.TOOL_FAILED));
            controller.getPayload().add(LLMChatMessage.ofToolResult(call.getId(), result));
            controller.getToolUsages().add(new ToolUsage(call.getName(), call.getArguments(), result, failed));
            try {
                controller.getListener().onToolCall(call.getName(), call.getArguments(), result, failed);
            } catch (Exception e) {
                log.debug("A listener of a chat failed: {}", e.getMessage());
            }
            next.returnFromEvent();
        });
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.CALL_TOOLS;
    }
}
