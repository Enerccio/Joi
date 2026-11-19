package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.ChatService;
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
import java.util.List;

/** Her system prompt, the latest messages ({@link ChatService#HISTORY}) and what the user said. */
public class PayloadStep extends TurnStepBase {

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        String system = personaService.renderChatSystemPrompt(controller.getJoi(), null, controller.getMood().key(),
                controller.getTemplateData());
        List<LLMChatMessage> payload = new ArrayList<>();
        payload.add(LLMChatMessage.of(LLMRole.SYSTEM, system));
        for (ChatMessage earlier : chatMessageService.findLatest(controller.getJoi(), ChatService.HISTORY)) {
            if (StringUtils.isNotBlank(earlier.getInput())) {
                payload.add(LLMChatMessage.of(LLMRole.USER, earlier.getInput()));
            }
            if (StringUtils.isNotBlank(earlier.getResponse())) {
                payload.add(LLMChatMessage.of(LLMRole.ASSISTANT, earlier.getResponse()));
            }
        }
        payload.add(LLMChatMessage.of(LLMRole.USER, controller.getInput().text()));
        controller.setPayload(payload);
        controller.emitEvent(TurnEvents.AFTER_PREPARE_PAYLOAD, controller::next);
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.PAYLOAD;
    }
}
