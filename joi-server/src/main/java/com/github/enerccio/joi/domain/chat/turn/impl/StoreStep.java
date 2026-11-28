package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.MessagePromptService;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The turn stored as a {@link ChatMessage}, with its prompt (the calls of her functions and their results included). */
public class StoreStep extends TurnStepBase {

    private static final Logger log = LoggerFactory.getLogger(StoreStep.class);

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MessagePromptService messagePromptService;

    @Autowired
    private ChatImageService chatImageService;

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
            message.setImages(ChatMessage.toJson(controller.getInput().images()));
            if (!controller.getInput().images().isEmpty()) {
                try {
                    message.setImageTokens(chatImageService.tokens(controller.getInput().images()));
                } catch (Exception e) {
                    log.debug("The images of a message could not be measured", e);
                }
            }
            @SuppressWarnings("unchecked")
            List<String> imageNotes = (List<String>) controller.getProperties().get(TurnProperties.IMAGE_NOTES);
            message.setImageNotes(ChatMessage.toJson(imageNotes));
            @SuppressWarnings("unchecked")
            List<String> toolImages = (List<String>) controller.getProperties().get(TurnProperties.TOOL_IMAGES);
            message.setToolImages(toolImages == null || toolImages.isEmpty() ? null : ChatMessage.toJson(toolImages));
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
            if (!controller.getInput().images().isEmpty()) {
                try {
                    chatImageService.attach(message, controller.getInput().images());
                } catch (Exception e) {
                    log.warn("The images of a message could not be linked to it", e);
                }
            }
            if (!message.toolImageUuids().isEmpty()) {
                try {
                    chatImageService.attach(message, message.toolImageUuids());
                } catch (Exception e) {
                    log.warn("The pictures of her tools could not be linked to her message", e);
                }
            }
            messagePromptService.store(message, promptText(controller.getPayload()));
            controller.setMessage(message);
            try {
                controller.getListener().onReply(message);
            } catch (Exception e) {
                // a caller that is gone (a stream whose client left) does not stop her speech and what follows the store
                log.debug("A listener of a chat failed: {}", e.getMessage());
            }
            // a turn that failed after some rounds is kept as it was, and not spoken: it goes to its end
            controller.emitEvent(TurnEvents.AFTER_STORE, () -> {
                if (Boolean.TRUE.equals(controller.getProperties().get(TurnProperties.PARTIAL))) {
                    controller.jumpTo(TurnStepType.CLEANUP);
                } else {
                    controller.next();
                }
            });
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
            if (message.hasAttachments()) {
                // what was attached, never the bytes: the prompt is stored with the message and the image is its own resource
                JsonArray attachments = new JsonArray();
                for (LLMAttachment attachment : message.getAttachments()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("name", attachment.getName());
                    a.addProperty("mimeType", attachment.getMimeType());
                    a.addProperty("size", attachment.getData() == null ? 0 : attachment.getData().length);
                    attachments.add(a);
                }
                entry.add("attachments", attachments);
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
