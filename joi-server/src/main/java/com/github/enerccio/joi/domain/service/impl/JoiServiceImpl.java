package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.persona.Personality;
import com.github.enerccio.joi.domain.repository.JoiRepository;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

public class JoiServiceImpl extends ExtendableServiceImpl<Joi, JoiRepository> implements JoiService {

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private QueuedEventService queuedEventService;

    @Autowired
    private TtsService ttsService;

    @Override
    @CommonTx
    public Joi create(String name) throws Exception {
        Joi joi = new Joi();
        joi.setAssistantName(StringUtils.defaultIfBlank(StringUtils.strip(name), null));
        return save(joi);
    }

    @Override
    public Persona persona(Joi joi) throws Exception {
        Personality personality = personaService.personality(joi.getPersonality());
        String actorId = StringUtils.defaultIfBlank(joi.getVoiceActor(), personaService.getUserSetting().getVoiceActor());
        Actor actor = actorId == null ? null : ttsService.actors().stream().filter(a -> actorId.equals(a.getId())).findFirst().orElse(null);
        if (personality == null) {
            personality = personaService.currentPersonality();
        }
        return new Persona(name(joi),
                StringUtils.isNotBlank(joi.getUserName()) ? joi.getUserName().strip() : personaService.userName(),
                personality == null ? null : personality.key(),
                personality == null ? null : personality.name(),
                actor == null ? null : actor.getId(), actor == null ? null : actor.getName(), actor == null ? null : actor.getDescription());
    }

    @Override
    public String name(Joi joi) throws Exception {
        return StringUtils.isNotBlank(joi.getAssistantName()) ? joi.getAssistantName().strip() : personaService.assistantName();
    }

    @Override
    @CommonTxReadOnly
    public List<Joi> findAllByName() throws Exception {
        List<Joi> jois = new ArrayList<>(findAllForUser());
        Map<Long, String> names = new HashMap<>();
        for (Joi joi : jois) {
            names.put(joi.getId(), name(joi));
        }
        jois.sort(Comparator.comparing((Joi joi) -> names.get(joi.getId()), String.CASE_INSENSITIVE_ORDER));
        return jois;
    }

    @Override
    @CommonTx
    public Joi update(Joi joi, java.util.function.Consumer<Joi> change) throws Exception {
        Joi stored = find(joi.getId());
        if (stored == null) {
            throw new IllegalArgumentException("No assistant " + joi.getId());
        }
        change.accept(stored);
        return save(stored);
    }

    @Override
    @CommonTx
    public Joi saveMood(Joi joi, String mood, java.util.Date since) throws Exception {
        Joi saved = update(joi, stored -> {
            stored.setMood(mood);
            stored.setMoodSince(since);
        });
        joi.setMood(mood);
        joi.setMoodSince(since);
        return saved;
    }

    @Override
    @CommonTx
    public List<QueuedEvent> saveAbout(Joi joi, Persona before) throws Exception {
        Joi saved = save(joi);
        List<QueuedEvent> events = new ArrayList<>();
        if (before == null || !chatMessageService.hasAny(saved)) {
            return events;
        }
        Persona after = persona(saved);
        JoiDefaults defaults = personaService.defaults();
        if (!Objects.equals(before.assistantName(), after.assistantName())) {
            events.add(queue(saved, JoiEvents.RENAMED, defaults.eventRenamed(before.assistantName(), after.assistantName()),
                    before.assistantName(), after.assistantName()));
        }
        if (!Objects.equals(before.userName(), after.userName())) {
            events.add(queue(saved, JoiEvents.USER_RENAMED, defaults.eventUserRenamed(before.userName(), after.userName()),
                    before.userName(), after.userName()));
        }
        if (!Objects.equals(before.personalityKey(), after.personalityKey())) {
            events.add(queue(saved, JoiEvents.PERSONALITY_CHANGED,
                    defaults.eventPersonalityChanged(before.personalityName(), after.personalityName()),
                    before.personalityKey(), after.personalityKey()));
        }
        if (!Objects.equals(before.voiceActorId(), after.voiceActorId())) {
            events.add(queue(saved, JoiEvents.VOICE_CHANGED,
                    defaults.eventVoiceChanged(before.voiceActorName(), after.voiceActorName(), after.voiceDescription()),
                    before.voiceActorId(), after.voiceActorId()));
        }
        // delivered when a node of hers connects; while one is connected the event dispatcher takes them at once (to come
        // with the nodes, see DESIGN.md → Events)
        return events;
    }

    private QueuedEvent queue(Joi joi, String kind, String text, String from, String to) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("from", from);
        payload.addProperty("to", to);
        QueuedEvent event = new QueuedEvent();
        event.setJoi(joi);
        event.setKind(kind);
        event.setOccurredAt(AppClock.now());
        event.setText(text);
        event.setPayload(payload.toString());
        return queuedEventService.save(event);
    }
}
