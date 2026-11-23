package com.github.enerccio.joi.domain.chat.turn.impl;

import com.github.enerccio.joi.domain.chat.turn.TurnController;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnStepBase;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.tts.model.Actor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

/** Her as she is now, today's mood and the data of her system prompt: her node, her body, her voice. */
public class PrepareStep extends TurnStepBase {

    @Autowired
    private JoiService joiService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private com.github.enerccio.joi.domain.service.ProfileService profileService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private SummarizerService summarizerService;

    /** How many memories her prompt recalls at most. */
    private static final int MEMORIES = 20;

    @Override
    protected void onStep(TurnController controller) throws Exception {
        controller.emitEvent(TurnEvents.BEFORE_PREPARE, () -> {
            Joi joi = joiService.find(controller.getJoi().getId());
            controller.setJoi(joi);
            controller.setMood(chatService.todaysMood(joi));

            ChatTemplateData data = new ChatTemplateData();
            data.setNode(StringUtils.defaultIfBlank(controller.getInput().node(), null));
            // she is in the profile of the node that talks to her; when it is another one than her last turn was in, she is told
            String profile = controller.getInput().profile();
            String last = profileService.last(joi);
            data.setBody(bodyService.prompt(joi, profile, last));
            if (!com.github.enerccio.joi.domain.bodies.Profiles.idOf(profile).equals(last)) {
                profileService.used(joi, profile);
            }
            data.setMemories(memoryService.important(joi, MEMORIES));
            data.setSummaries(summarizerService.prompt(joi));
            Actor voice = chatService.ttsModel(joi) == null ? null : chatService.voiceActor(joi);
            data.setVoice(voice == null ? null : StringUtils.defaultIfBlank(voice.getDescription(), null));
            controller.setTemplateData(data);
            controller.emitEvent(TurnEvents.AFTER_TEMPLATE_DATA, controller::next);
        });
    }

    @Override
    public TurnStepType getType() {
        return TurnStepType.PREPARE;
    }
}
