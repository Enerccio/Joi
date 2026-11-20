package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.chat.MessageStamps;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.joi.domain.templates.RecalledMemoriesTemplateData;
import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Her system prompt, the latest messages ({@link ChatService#HISTORY}) and what the user said. */
public class PayloadStep extends TurnStepBase {

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MemoryService memoryService;

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
        List<ChatMessage> window = chatMessageService.findLatest(joi, ChatService.HISTORY + 1);
        Date previous = null;
        if (window.size() > ChatService.HISTORY) {
            previous = window.getFirst().getSentAt();
            window = window.subList(1, window.size());
        }
        // the first message of the prompt always has its time line, so she knows the day; the others when a pause was
        // before them, see MessageStamps
        boolean first = true;
        for (ChatMessage earlier : window) {
            if (StringUtils.isNotBlank(earlier.getInput())) {
                payload.add(LLMChatMessage.of(LLMRole.USER,
                        MessageStamps.stamped(personaService, joi, earlier.getInput(), earlier.getSentAt(), previous, first)));
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
        String recalled = memoryService.recalled(joi, said, RECALLED);
        if (recalled != null) {
            RecalledMemoriesTemplateData data = new RecalledMemoriesTemplateData();
            data.setMemories(recalled);
            String block = personaService.render(joi, JoiPromptField.RECALLED_MEMORIES, data);
            if (StringUtils.isNotBlank(block)) {
                text.append('\n').append(block);
            }
        }
        payload.add(LLMChatMessage.of(LLMRole.USER, text.toString()));
        controller.setPayload(payload);
        controller.emitEvent(TurnEvents.AFTER_PREPARE_PAYLOAD, controller::next);
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.PAYLOAD;
    }
}
