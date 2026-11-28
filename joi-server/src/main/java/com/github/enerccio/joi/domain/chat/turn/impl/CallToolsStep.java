package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ToolEffects;
import com.github.enerccio.joi.domain.chat.ToolImages;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ToolImagesTemplateData;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.service.JoiWorkspaceService;
import com.github.enerccio.marginalia.domain.service.agent.ShownImages;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    private JoiWorkspaceService workspaceService;

    @Autowired
    private ToolImages toolImages;

    @Autowired
    private ChatImageService chatImageService;

    @Autowired
    private PersonaService personaService;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        List<LLMToolCall> calls = controller.getProperties().get(TurnProperties.ROUND_CALLS) instanceof List<?> list
                ? list.stream().map(LLMToolCall.class::cast).toList() : List.of();
        // a result that came after its turn was stopped left its pictures: they are not of this round
        toolImages.take(controller.getJoi().getId());
        forEachAsync(controller, calls, (call, next) -> call(controller, call, next), () -> afterCalls(controller, calls));
    }

    /**
     * The model reads the results in another round, except when it has said its words in this one and only gestured (an animation, a mood, a
     * notice): that round has nothing to add, and a small model fills it with nothing until its budget is gone, so the turn ends here.
     */
    private void afterCalls(TurnController controller, List<LLMToolCall> calls) throws Exception {
        // the pictures she asked for with view_image: a tool result is text only, so they come in a message of their own after the results
        if (controller.getProperties().get(TurnProperties.SHOWN_IMAGES) instanceof ShownImages shown) {
            var looked = workspaceService.shownImages(controller.getJoi(), shown);
            if (looked != null) {
                controller.getPayload().add(looked);
            }
        }
        // the pictures of a node's results (tool_result.images): the same, in a message after the results
        List<ToolImages.Shown> pictures = toolImages.take(controller.getJoi().getId());
        if (!pictures.isEmpty()) {
            LLMChatMessage message = toolImagesMessage(controller, pictures);
            if (message != null) {
                controller.getPayload().add(message);
            }
        }
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

    /**
     * The pictures as her chat model takes them: attached when it sees, else described by the user's model that can see. A picture
     * that is not the user's (or gone) is left out; null when none is left.
     */
    private LLMChatMessage toolImagesMessage(TurnController controller, List<ToolImages.Shown> pictures) throws Exception {
        Joi joi = controller.getJoi();
        boolean sees = controller.getAi().isMultimodal();
        LLMChatMessage message = LLMChatMessage.of(LLMRole.USER, "");
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        StringBuilder shows = new StringBuilder();
        int shown = 0;
        for (ToolImages.Shown picture : pictures) {
            if (chatImageService.find(picture.image()) == null) {
                log.debug("A picture of {} is not the user's or is gone: {}", picture.tool(), picture.image());
                continue;
            }
            if (sees) {
                LLMAttachment attachment = chatImageService.attachment(picture.image());
                if (attachment == null) {
                    continue;
                }
                message.addAttachment(attachment);
            } else {
                String note;
                try {
                    note = chatImageService.describe(joi, picture.image(), "");
                } catch (Exception e) {
                    note = "(it could not be described: " + e.getMessage() + ")";
                }
                shows.append(shows.isEmpty() ? "" : "\n").append(shown + 1).append(". ").append(note);
            }
            tools.add(picture.tool());
            shown++;
            // kept with her message, shown in the transcript
            @SuppressWarnings("unchecked")
            List<String> kept = (List<String>) controller.getProperties().computeIfAbsent(TurnProperties.TOOL_IMAGES, k -> new java.util.ArrayList<String>());
            if (!kept.contains(picture.image())) {
                kept.add(picture.image());
            }
        }
        if (shown == 0) {
            return null;
        }
        ToolImagesTemplateData data = new ToolImagesTemplateData();
        data.setTools(String.join(", ", tools));
        data.setShows(shows.toString());
        message.setContent(personaService.render(joi, JoiPromptField.TOOL_IMAGES, data));
        return message;
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
            boolean kept = !failed && TurnProperties.kept(properties, call.getName());
            controller.getToolUsages().add(new ToolUsage(call.getName(), call.getArguments(), result, failed, kept));
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
