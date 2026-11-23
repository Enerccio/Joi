package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ChatService;
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

/** Her system prompt, the latest messages no summary covers ({@link ChatService#HISTORY}) and what the user said. */
public class PayloadStep extends TurnStepBase {

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
        // one more than the prompt holds: the message before the first one decides whether that one gets a time line
        // what a summary covers is in her system prompt instead, so the history starts where the summaries end
        List<ChatMessage> window = chatMessageService.findLatestExchangesSince(joi, summarizerService.coveredUpTo(joi), ChatService.HISTORY + 1);
        Date previous = null;
        if (window.size() > ChatService.HISTORY) {
            previous = window.getFirst().getSentAt();
            window = window.subList(1, window.size());
        }
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
            if (StringUtils.isNotBlank(earlier.getResponse())) {
                payload.add(LLMChatMessage.of(LLMRole.ASSISTANT, earlier.getResponse()));
            }
            previous = earlier.getSentAt();
        }
        // what was said now: its time line, and the memories that fit it after it (they are not stored, so they are in
        // this prompt only; the always recalled ones are in the system prompt)
        String said = controller.getInput().text();
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
