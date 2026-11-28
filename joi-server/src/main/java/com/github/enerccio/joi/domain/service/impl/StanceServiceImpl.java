package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.domain.service.StanceService;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Her stance on her ({@link Joi#getStance()}), told to her nodes and noted in her conversation.
 */
public class StanceServiceImpl implements StanceService {

    /** The kind of the note when she took a stance. */
    public static final String TAKEN = "stance.taken";
    /** The kind of the note when she ended it herself. */
    public static final String LEFT = "stance.left";

    @Autowired
    private JoiService joiService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private QueuedEventService queuedEventService;

    @Autowired
    private PersonaService personaService;

    @Override
    public AnimationPack current(Joi joi) throws Exception {
        String uuid = joiService.find(joi.getId()).getStance();
        return uuid == null ? null : packService.findForUser(uuid);
    }

    @Override
    public boolean take(Joi joi, AnimationPack animation) throws Exception {
        AnimationPack before = current(joi);
        if (before != null && before.getUuid().equals(animation.getUuid())) {
            return false;
        }
        save(joi, animation.getUuid());
        chatService.log(joi, TAKEN, "She takes the stance " + name(animation)
                + (before == null ? "" : " (was " + name(before) + ")") + ".");
        nodeService.stance(joi);
        return true;
    }

    @Override
    public AnimationPack end(Joi joi, boolean byUser) throws Exception {
        String uuid = joiService.find(joi.getId()).getStance();
        if (uuid == null) {
            return null;
        }
        AnimationPack before = packService.findForUser(uuid);
        save(joi, null);
        String name = before == null ? "?" : name(before);
        chatService.log(joi, byUser ? JoiEvents.STANCE_ENDED : LEFT,
                "Her stance " + name + " ended" + (byUser ? ": you ended it." : ": she ended it."));
        if (byUser && before != null && chatMessageService.hasAny(joi)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("from", uuid);
            QueuedEvent event = new QueuedEvent();
            event.setJoi(joi);
            event.setKind(JoiEvents.STANCE_ENDED);
            event.setOccurredAt(AppClock.now());
            event.setText(personaService.defaults().eventStanceEnded(name));
            event.setPayload(payload.toString());
            queuedEventService.save(event);
        }
        nodeService.stance(joi);
        return before;
    }

    private void save(Joi joi, String uuid) throws Exception {
        joiService.update(joi, stored -> stored.setStance(uuid));
        joi.setStance(uuid);
    }

    private static String name(AnimationPack pack) {
        return StringUtils.defaultIfBlank(pack.getName(), "unnamed");
    }
}
