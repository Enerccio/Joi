package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.chat.ToolUsage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.joi.domain.summary.MessageTokens;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.joi.domain.chat.MessageStamps;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.domain.templates.RecalledMemoriesTemplateData;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.templates.ImageNoteTemplateData;
import com.github.enerccio.joi.domain.chat.turn.TurnProperties;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Her system prompt, every message no summary covers and what the user said. Her history has no count: when the prompt would pass
 * {@link JoiDefaults#HISTORY_PROMPT_PERCENT} of her chat model's context, the turn has the oldest messages summarized first
 * ({@code SummarizerService.makeRoom}), waits for it and builds the prompt again, and she is told of the summaries with the words.
 */
public class PayloadStep extends TurnStepBase {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PayloadStep.class);

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ChatImageService chatImageService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private SummarizerService summarizerService;

    /** How many memories that fit what was said are added to it at most. */
    private static final int RECALLED = 10;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        Joi joi = controller.getJoi();
        String system = personaService.renderChatSystemPrompt(controller.getJoi(), null, controller.getMood().key(),
                controller.getTemplateData());
        List<LLMChatMessage> payload = new ArrayList<>();
        payload.add(LLMChatMessage.of(LLMRole.SYSTEM, system));
        // what a summary covers is in her system prompt instead, so the history starts where the summaries end; it has no count, so it
        // stays the same from turn to turn (the cache of the prompt keeps it) until a summary is made
        List<ChatMessage> window = chatMessageService.findLatestExchangesSince(joi, summarizerService.coveredUpTo(joi), Integer.MAX_VALUE);
        if (!Boolean.TRUE.equals(controller.getProperties().get(TurnProperties.ROOM_ASKED)) && overfills(controller, system, window)) {
            // it would not fit her chat model's context: the oldest are summarized now, then the prompt is made again (once)
            controller.getProperties().put(TurnProperties.ROOM_ASKED, Boolean.TRUE);
            summarizerService.makeRoom(joi).whenComplete((events, error) -> controller.wrapCallback(() -> {
                if (error != null) {
                    log.warn("Her history could not be summarized to fit the context: {}", error.getMessage(), error);
                } else if (!events.isEmpty()) {
                    controller.getProperties().put(TurnProperties.SUMMARY_EVENTS, List.copyOf(events));
                }
                controller.jumpTo(TurnStepType.PAYLOAD);
            }).run());
            return;
        }
        Date previous = null;
        // the first message of the prompt always has its time line, so she knows the day; the others when a pause was
        // before them, see MessageStamps
        boolean first = true;
        boolean sees = controller.getAi().isMultimodal();
        for (ChatMessage earlier : window) {
            if (StringUtils.isNotBlank(earlier.getInput()) || !earlier.imageUuids().isEmpty()) {
                // an image is in the message the same way in every prompt that holds it: the file as it was kept, or the note written once
                payload.add(withImages(LLMChatMessage.of(LLMRole.USER,
                                MessageStamps.stamped(personaService, joi, StringUtils.defaultString(earlier.getInput()), earlier.getSentAt(), previous, first)),
                        joi, earlier.imageUuids(), earlier.imageNoteTexts(), sees));
                first = false;
            }
            payload.addAll(keptCalls(earlier));
            if (StringUtils.isNotBlank(earlier.getResponse())) {
                payload.add(LLMChatMessage.of(LLMRole.ASSISTANT, earlier.getResponse()));
            }
            previous = earlier.getSentAt();
        }
        // what was said now: its time line, and the memories that fit it after it (they are not stored, so they are in
        // this prompt only; the always recalled ones are in the system prompt)
        String said = inputText(controller);
        StringBuilder text = new StringBuilder(MessageStamps.stamped(personaService, joi, said, controller.getSent(), previous, first));
        List<String> images = controller.getInput().images();
        List<String> notes = List.of();
        if (!images.isEmpty() && !sees) {
            // the chat model cannot see: a model that can writes what the image shows, once, and the note is stored with the message
            notes = new ArrayList<>();
            for (String image : images) {
                try {
                    notes.add(chatImageService.describe(joi, image, said));
                } catch (Exception e) {
                    throw new IllegalStateException("The image could not be described for her: " + e.getMessage(), e);
                }
            }
            controller.getProperties().put(TurnProperties.IMAGE_NOTES, notes);
        }
        String recalled = memoryService.recalled(joi, said, RECALLED);
        if (recalled != null) {
            RecalledMemoriesTemplateData data = new RecalledMemoriesTemplateData();
            data.setMemories(recalled);
            String block = personaService.render(joi, JoiPromptField.RECALLED_MEMORIES, data);
            if (StringUtils.isNotBlank(block)) {
                text.append('\n').append(block);
            }
        }
        payload.add(withImages(LLMChatMessage.of(LLMRole.USER, text.toString()), joi, images, notes, sees));
        controller.setPayload(payload);
        controller.emitEvent(TurnEvents.AFTER_PREPARE_PAYLOAD, controller::next);
    }

    /**
     * The calls of an earlier exchange whose results stay in her history ({@code ToolUsage.kept}), as they happened: her call, then what
     * each answered, before her reply. Built from what was stored, with ids made from the message and the call's place, so it reads the
     * same in every prompt and the cache keeps it. Empty when none was kept: the calls that only did something leave no trace.
     */
    static List<LLMChatMessage> keptCalls(ChatMessage earlier) {
        List<ToolUsage> kept = ToolUsage.fromJson(earlier.getToolCalls()).stream().filter(ToolUsage::kept).toList();
        if (kept.isEmpty()) {
            return List.of();
        }
        List<LLMToolCall> calls = new ArrayList<>();
        List<LLMChatMessage> results = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            ToolUsage usage = kept.get(i);
            String id = "kept_" + earlier.getId() + "_" + i;
            calls.add(LLMToolCall.of(id, usage.name(), StringUtils.defaultIfBlank(usage.arguments(), "{}")));
            results.add(LLMChatMessage.ofToolResult(id, StringUtils.defaultString(usage.result())));
        }
        List<LLMChatMessage> messages = new ArrayList<>();
        messages.add(LLMChatMessage.ofToolCalls("", calls));
        messages.addAll(results);
        return messages;
    }

    /**
     * The words of the turn as she gets them and as they are stored: what she is told of the summaries the turn made first, if any,
     * then the input.
     */
    public static String inputText(TurnController controller) {
        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) controller.getProperties().get(TurnProperties.SUMMARY_EVENTS);
        String input = controller.getInput().text();
        if (events == null || events.isEmpty()) {
            return input;
        }
        String told = String.join("\n\n", events);
        return StringUtils.isBlank(input) ? told : told + "\n\n" + input;
    }

    /**
     * Whether the prompt (her system prompt, the history, the words and images of the turn; about, see {@link MessageTokens}) would pass
     * {@link JoiDefaults#HISTORY_PROMPT_PERCENT} of her chat model's context; never when the model does not say how long it is, or
     * with no history to summarize.
     */
    private boolean overfills(TurnController controller, String system, List<ChatMessage> window) throws Exception {
        AI ai = controller.getAi();
        if (ai == null || ai.getMaxContext() == null || ai.getMaxContext() <= 0 || window.size() < 2) {
            return false;
        }
        long budget = (long) ai.getMaxContext() * JoiDefaults.HISTORY_PROMPT_PERCENT / 100;
        long tokens = (StringUtils.length(system) + StringUtils.length(controller.getInput().text()) + MessageTokens.CHARS_PER_TOKEN - 1)
                / MessageTokens.CHARS_PER_TOKEN + MessageTokens.of(window) + chatImageService.tokens(controller.getInput().images());
        return tokens > budget;
    }

    /**
     * The images of a message: as attachments when the chat model can see, else as the notes written about them, put after the words.
     * A message of the history whose note was never written (the model was changed since) says so, as it says it in every prompt.
     */
    private LLMChatMessage withImages(LLMChatMessage message, Joi joi, List<String> images, List<String> notes, boolean sees)
            throws Exception {
        if (images.isEmpty()) {
            return message;
        }
        if (sees) {
            for (String image : images) {
                LLMAttachment attachment = chatImageService.attachment(image);
                if (attachment != null) {
                    message.addAttachment(attachment);
                }
            }
            return message;
        }
        StringBuilder text = new StringBuilder(StringUtils.defaultString(message.getContent()));
        for (int i = 0; i < images.size(); i++) {
            ImageNoteTemplateData data = new ImageNoteTemplateData();
            data.setShows(i < notes.size() && StringUtils.isNotBlank(notes.get(i)) ? notes.get(i) : "(it was not described)");
            String note = personaService.render(joi, JoiPromptField.IMAGE_NOTE, data);
            if (StringUtils.isNotBlank(note)) {
                text.append(text.isEmpty() ? "" : "\n").append(note);
            }
        }
        message.setContent(text.toString());
        return message;
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.PAYLOAD;
    }
}
