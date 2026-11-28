package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.animations.StanceLog;
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
    public boolean take(Joi joi, AnimationPack animation, String reason) throws Exception {
        AnimationPack before = current(joi);
        if (before != null && before.getUuid().equals(animation.getUuid())) {
            return false;
        }
        String why = StringUtils.isBlank(reason) ? null : StanceLog.oneLine(reason);
        save(joi, animation.getUuid(), why, why == null ? null : "she took " + name(animation) + ": " + why, AppClock.now());
        chatService.log(joi, TAKEN, "She takes the stance " + name(animation)
                + (before == null ? "" : " (was " + name(before) + ")") + (why == null ? "." : ": " + why));
        nodeService.stance(joi);
        return true;
    }

    @Override
    public AnimationPack end(Joi joi, boolean byUser, String reason) throws Exception {
        String uuid = joiService.find(joi.getId()).getStance();
        if (uuid == null) {
            return null;
        }
        AnimationPack before = packService.findForUser(uuid);
        java.util.Date now = AppClock.now();
        String why = byUser ? "the user ended it in your dialog" : StringUtils.isBlank(reason) ? null : StanceLog.oneLine(reason);
        save(joi, null, null, why, now);
        String name = before == null ? "?" : name(before);
        chatService.log(joi, byUser ? JoiEvents.STANCE_ENDED : LEFT,
                "Her stance " + name + " ended" + (byUser ? ": you ended it." : why == null ? ": she ended it." : ": she ended it: " + why));
        if (byUser && before != null && chatMessageService.hasAny(joi)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("from", uuid);
            QueuedEvent event = new QueuedEvent();
            event.setJoi(joi);
            event.setKind(JoiEvents.STANCE_ENDED);
            event.setOccurredAt(now);
            event.setText(personaService.defaults().eventStanceEnded(name));
            event.setPayload(payload.toString());
            queuedEventService.save(event);
        }
        nodeService.stance(joi);
        return before;
    }

    /**
     * Her stance is now {@code uuid} (null for none), taken for {@code why}; the one she held goes into her ledger as
     * ended now, for {@code endedWhy}.
     */
    private void save(Joi joi, String uuid, String why, String endedWhy, java.util.Date now) throws Exception {
        Joi saved = joiService.update(joi, stored -> {
            if (stored.getStance() != null) {
                long from = stored.getStanceSince() == null ? 0 : stored.getStanceSince().getTime();
                stored.setStanceLog(stored.stanceLedger().with(new StanceLog.Entry(stored.getStance(), from, now.getTime(),
                        stored.getStanceReason(), endedWhy)).toJson());
            }
            stored.setStance(uuid);
            stored.setStanceSince(uuid == null ? null : now);
            stored.setStanceReason(uuid == null ? null : why);
        });
        joi.setStance(saved.getStance());
        joi.setStanceSince(saved.getStanceSince());
        joi.setStanceReason(saved.getStanceReason());
        joi.setStanceLog(saved.getStanceLog());
    }

    private static String name(AnimationPack pack) {
        return StringUtils.defaultIfBlank(pack.getName(), "unnamed");
    }
}
