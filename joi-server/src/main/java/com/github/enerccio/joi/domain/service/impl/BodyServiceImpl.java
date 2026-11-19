package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.bodies.Appearance;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.templates.BodyTemplateData;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BodyServiceImpl extends ExtendableServiceImpl<Body, BodyRepository> implements BodyService {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private QueuedEventService queuedEventService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private AnimationPackService packService;

    @Autowired
    private ResourceService resourceService;


    @Override
    @CommonTxReadOnly
    public List<Body> findForJoi(Joi joi) throws Exception {
        List<Body> bodies = new ArrayList<>(getRepository().findByJoi(joi));
        bodies.sort(Comparator.comparing((Body body) -> StringUtils.defaultString(body.getName()), String.CASE_INSENSITIVE_ORDER));
        return bodies;
    }

    @Override
    @CommonTxReadOnly
    public Body active(Joi joi) throws Exception {
        return getRepository().findByJoi(joi).stream().filter(Body::isActive).findFirst().orElse(null);
    }

    @Override
    @CommonTxReadOnly
    public List<Body> findByModel(Model model) throws Exception {
        return model.getId() == null ? List.of() : getRepository().findByModel(model);
    }

    @Override
    @CommonTx
    public Body create(Joi joi, String name) throws Exception {
        Body body = new Body();
        body.setJoi(joi);
        body.setName(StringUtils.defaultIfBlank(StringUtils.strip(name), null));
        body.setActive(active(joi) == null);
        Body saved = save(body);
        if (saved.isActive()) {
            // her first body: she is in it at once, and told so when she has chatted
            switched(joi, null, saved);
        }
        return saved;
    }

    @Override
    @CommonTx
    public List<QueuedEvent> activate(Joi joi, Body body) throws Exception {
        Body before = active(joi);
        if (body != null && !body.getJoi().getId().equals(joi.getId())) {
            throw new IllegalArgumentException("not a body of hers");
        }
        for (Body other : getRepository().findByJoi(joi)) {
            boolean active = body != null && other.getId().equals(body.getId());
            if (other.isActive() != active) {
                other.setActive(active);
                save(other);
            }
        }
        Body after = body == null ? null : find(body.getId());
        return switched(joi, before, after);
    }

    @Override
    @CommonTx
    public List<QueuedEvent> deleteBody(Body body) throws Exception {
        Body stored = find(body.getId());
        Joi joi = stored.getJoi();
        boolean wasActive = stored.isActive();
        stored.setActive(false);
        if (stored.getAnimationsResourceId() != null) {
            forgetAnimations(stored.getAnimationsResourceId());
            stored.setAnimationsResourceId(null);
        }
        delete(save(stored), false);
        return wasActive ? switched(joi, stored, null) : List.of();
    }

    /** The event of her switch from one body to another (each may be null), when she has chatted and it changed. */
    private List<QueuedEvent> switched(Joi joi, Body from, Body to) throws Exception {
        List<QueuedEvent> events = new ArrayList<>();
        Long fromId = from == null ? null : from.getId();
        Long toId = to == null ? null : to.getId();
        if (Objects.equals(fromId, toId) || !chatMessageService.hasAny(joi)) {
            return events;
        }
        JoiDefaults defaults = personaService.defaults();
        events.add(queue(joi, JoiEvents.BODY_SWITCHED, defaults.eventBodySwitched(from == null ? null : name(from),
                        to == null ? null : name(to), to == null ? null : appearance(to)),
                from == null ? null : from.getUuid(), to == null ? null : to.getUuid()));
        return events;
    }

    private QueuedEvent queue(Joi joi, String kind, String text, String from, String to) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("from", from);
        payload.addProperty("to", to);
        return queue(joi, kind, text, payload);
    }

    private QueuedEvent queue(Joi joi, String kind, String text, JsonObject payload) throws Exception {
        QueuedEvent event = new QueuedEvent();
        event.setJoi(joi);
        event.setKind(kind);
        event.setOccurredAt(AppClock.now());
        event.setText(text);
        event.setPayload(payload.toString());
        return queuedEventService.save(event);
    }

    @Override
    @CommonTxReadOnly
    public String appearance(Body body) throws Exception {
        Model model = body.getModel() == null ? null : modelService.find(body.getModel().getId());
        return model == null || model.isDeleted() ? null : Appearance.of(model, BodyState.toggles(model, body.partStateMap()));
    }

    /** The body's full model, null for none or a deleted one. */
    private Model model(Body body) throws Exception {
        Model model = body.getModel() == null ? null : modelService.find(body.getModel().getId());
        return model == null || model.isDeleted() ? null : model;
    }

    @Override
    @CommonTxReadOnly
    public Map<String, Boolean> toggles(Body body) throws Exception {
        Model model = model(body);
        return model == null ? new LinkedHashMap<>() : BodyState.toggles(model, body.partStateMap());
    }

    @Override
    @CommonTx
    public List<QueuedEvent> switchPart(Body body, String partId, String stateId) throws Exception {
        Body stored = find(body.getId());
        Model model = model(stored);
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        DescribedPart part = model == null ? null : DescribedPart.of(model, capabilities).stream()
                .filter(candidate -> candidate.id().equals(partId)).findFirst().orElse(null);
        DescribedPart.State state = part == null ? null : BodyState.state(part, stateId);
        if (state == null) {
            throw new IllegalArgumentException("no such part or state: " + partId + " / " + stateId);
        }
        Map<String, String> states = stored.partStateMap();
        DescribedPart.State before = BodyState.stateOf(model, part, states);
        if (part.isDefault(state)) {
            states.remove(part.id());
        } else {
            states.put(part.id(), state.id());
        }
        stored.setPartStateMap(states);
        stored = save(stored);
        List<QueuedEvent> events = new ArrayList<>();
        if (stored.isActive() && (before == null || !before.id().equals(state.id())) && chatMessageService.hasAny(stored.getJoi())) {
            JsonObject payload = new JsonObject();
            payload.addProperty("to", stored.getUuid());
            payload.addProperty("part", part.id());
            payload.addProperty("fromState", before == null ? null : before.id());
            payload.addProperty("toState", state.id());
            payload.addProperty("by", "user");
            String stateName = part.group() ? state.name() : DescribedPart.SHOWN.equals(state.id()) ? "shown" : "hidden";
            events.add(queue(stored.getJoi(), JoiEvents.PART_SWITCHED,
                    personaService.defaults().eventPartSwitched(part.name(), stateName, part.group(), part.sentence(model, state.id())), payload));
        }
        return events;
    }

    @Override
    @CommonTx
    public List<QueuedEvent> modelDescribed(Model model) throws Exception {
        List<QueuedEvent> events = new ArrayList<>();
        JoiDefaults defaults = personaService.defaults();
        for (Body body : findByModel(model)) {
            String appearance = appearance(body);
            if (body.isActive() && appearance != null && body.getModel() != null && body.getModel().getId().equals(model.getId())
                    && chatMessageService.hasAny(body.getJoi())) {
                events.add(queue(body.getJoi(), JoiEvents.BODY_DESCRIBED, defaults.eventBodyDescribed(name(body), appearance),
                        null, body.getUuid()));
            }
        }
        return events;
    }

    @Override
    @CommonTxReadOnly
    public String prompt(Joi joi) throws Exception {
        List<Body> bodies = getRepository().findByJoi(joi);
        Body active = bodies.stream().filter(Body::isActive).findFirst().orElse(null);
        if (active == null) {
            return null;
        }
        List<String> expressions = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        Model model = active.getModel() == null ? null : modelService.find(active.getModel().getId());
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        if (capabilities != null) {
            for (ModelCapabilities.Expression expression : capabilities.expressions()) {
                if (expression.kind().equals("mood") || expression.kind().equals("custom")) {
                    expressions.add(expression.name());
                }
            }
            // only the parts described apart can be switched; each in the state the body is in
            Map<String, String> states = active.partStateMap();
            for (DescribedPart part : DescribedPart.of(model, capabilities)) {
                DescribedPart.State in = BodyState.stateOf(model, part, states);
                if (part.group()) {
                    List<String> others = part.states().stream().filter(s -> s != in).map(DescribedPart.State::name).toList();
                    parts.add(part.name() + ": " + (in == null ? "?" : in.name()) + (others.isEmpty() ? "" : " (or " + String.join(", ", others) + ")"));
                } else {
                    parts.add(part.name() + " (" + (in == null ? "?" : in.name()) + ")");
                }
            }
        }
        List<String> others = bodies.stream().filter(body -> !body.isActive()).map(this::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        BodyTemplateData data = new BodyTemplateData();
        data.setBodyName(name(active));
        data.setAppearance(StringUtils.defaultIfBlank(StringUtils.strip(appearance(active)), null));
        data.setExpressions(expressions.isEmpty() ? null : String.join(", ", expressions));
        data.setParts(parts.isEmpty() ? null : String.join(", ", parts));
        data.setOtherBodies(others.isEmpty() ? null : String.join(", ", others));
        return personaService.render(joiService.find(joi.getId()), JoiPromptField.BODY_PROMPT, data).strip();
    }

    private String name(Body body) {
        return StringUtils.defaultIfBlank(body.getName(), "unnamed");
    }

    @Override
    @CommonTxReadOnly
    public List<AnimationPack> animations(Body body) throws Exception {
        List<AnimationPack> all = packService.findAllByName();
        List<String> chosen = body.animationUuids();
        return chosen == null ? all : all.stream().filter(pack -> chosen.contains(pack.getUuid())).toList();
    }

    @Override
    @CommonTxReadOnly
    public File animationsFile(Body body) throws Exception {
        if (body.getAnimationsResourceId() == null) {
            return null;
        }
        Resource resource = resourceService.find(body.getAnimationsResourceId());
        return resource == null || resource.isDeleted() || resource.getOwner() == null || !resource.getOwner().getId().equals(currentUser.getId())
                ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTx
    public Body linkAnimations(Body body, File joia, String fingerprint, String hash) throws Exception {
        Body stored = find(body.getId());
        Long previous = stored.getAnimationsResourceId();
        if (joia == null) {
            stored.setAnimationsResourceId(null);
        } else {
            if (!modelService.uploadFolder().getCanonicalFile().equals(joia.getCanonicalFile().getParentFile())) {
                throw new IllegalArgumentException("not in the folder of resources: " + joia);
            }
            Resource resource = new Resource();
            resource.setOriginalName(JoiaFile.fileName(stored.getName()));
            resource.setMimeType(JoiaFile.CONTENT_TYPE);
            resource.setPath(joia.getName());
            resource.setSize(joia.length());
            resource.setHash(hash);
            resource = resourceService.save(resource);
            resourceService.link(resource, Body.class, stored.getId());
            stored.setAnimationsResourceId(resource.getId());
        }
        stored.setAnimationsFingerprint(fingerprint);
        stored.setAnimationsExportedAt(new Date());
        if (previous != null) {
            forgetAnimations(previous);
        }
        return save(stored);
    }

    private void forgetAnimations(Long resourceId) throws Exception {
        Resource old = resourceService.find(resourceId);
        if (old != null && !old.isDeleted()) {
            resourceService.softDelete(List.of(old.getUuid()));
        }
    }
}
