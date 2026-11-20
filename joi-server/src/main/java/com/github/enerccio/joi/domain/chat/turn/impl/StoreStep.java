package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.MessagePromptService;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The turn stored as a {@link ChatMessage}, with its prompt (the calls of her functions and their results included). */
public class StoreStep extends TurnStepBase {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MessagePromptService messagePromptService;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        controller.emitEvent(TurnEvents.BEFORE_STORE, () -> {
            Date sent = controller.getSent();
            ChatMessage message = new ChatMessage();
            message.setJoi(controller.getJoi());
            message.setType(controller.getInput().isEvent() ? ChatMessageType.EVENT : ChatMessageType.USER);
            message.setEventKind(controller.getInput().eventKind());
            message.setSentAt(sent);
            message.setInput(controller.getInput().text());
            message.setNode(StringUtils.defaultIfBlank(controller.getInput().node(), null));
            message.setResponse(StringUtils.defaultString(controller.getReply()));
            message.setResponseReasoning(StringUtils.defaultIfBlank(controller.getReasoning(), null));
            message.setToolCalls(ToolUsage.toJson(controller.getToolUsages()));
            message.setMood(controller.getMood().key());
            message.setModelUsed(controller.getAi().getName());
            message.setPromptTokens(controller.getPromptTokens());
            message.setTokenCount(controller.getCompletionTokens());
            message.setRequest(sent);
            if (controller.getFirstTokenMs() >= 0) {
                message.setTtft(new Date(sent.getTime() + controller.getFirstTokenMs()));
            }
            message.setResponseEnd(new Date(sent.getTime() + Math.max(0, controller.getTextMs())));
            message = chatMessageService.save(message);
            messagePromptService.store(message, promptText(controller.getPayload()));
            controller.setMessage(message);
            controller.getListener().onReply(message);
            controller.emitEvent(TurnEvents.AFTER_STORE, controller::next);
        });
    }

    /** The prompt as it is stored with the message: the messages as JSON. */
    static String promptText(List<LLMChatMessage> payload) {
        JsonArray messages = new JsonArray();
        for (LLMChatMessage message : payload) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole().name().toLowerCase(Locale.ROOT));
            entry.addProperty("content", message.getContent());
            if (message.hasToolCalls()) {
                JsonArray calls = new JsonArray();
                for (LLMToolCall call : message.getToolCalls()) {
                    JsonObject c = new JsonObject();
                    c.addProperty("id", call.getId());
                    c.addProperty("name", call.getName());
                    c.addProperty("arguments", call.getArguments());
                    calls.add(c);
                }
                entry.add("tool_calls", calls);
            }
            if (message.getToolCallId() != null) {
                entry.addProperty("tool_call_id", message.getToolCallId());
            }
            messages.add(entry);
        }
        return messages.toString();
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.STORE;
    }
}
