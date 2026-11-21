package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.bodies.Appearance;
import com.github.enerccio.joi.domain.bodies.BodyState;
import com.github.enerccio.joi.domain.bodies.DescribedPart;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.Outfit;
import com.github.enerccio.joi.domain.bodies.ToggleTree;
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
import com.github.enerccio.joi.domain.service.NodeService;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BodyServiceImpl extends ExtendableServiceImpl<Body, BodyRepository> implements BodyService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BodyServiceImpl.class);

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

    // the node service uses this one
    @Autowired
    @Lazy
    private NodeService nodeService;


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
            bodyChanged(joi);
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
        bodyChanged(joi);
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
        if (wasActive) {
            bodyChanged(joi);
        }
        return wasActive ? switched(joi, stored, null) : List.of();
    }

    /**
     * Her nodes read her body again: it changed (another body, a part, an outfit, her files). Told once the change is committed, or
     * the node would read what was before it; never a failure of the change itself.
     */
    private void bodyChanged(Joi joi) {
        Runnable tell = () -> {
            try {
                nodeService.notify(joi, "body");
            } catch (RuntimeException e) {
                log.debug("The nodes of an assistant were not told that her body changed: {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    tell.run();
                }
            });
        } else {
            tell.run();
        }
    }

    @Override
    @CommonTxReadOnly
    public void changed(Body body) throws Exception {
        Body stored = find(body.getId());
        if (stored.isActive()) {
            bodyChanged(stored.getJoi());
        }
    }

    @Override
    @CommonTxReadOnly
    public void modelChanged(Model model) throws Exception {
        for (Body body : findByModel(model)) {
            if (body.isActive() && body.getModel() != null && body.getModel().getId().equals(model.getId())) {
                bodyChanged(body.getJoi());
            }
        }
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
        // the outfits and the state are the stored ones, whatever the caller holds
        Body stored = body.getId() == null ? body : find(body.getId());
        Model model = stored.getModel() == null ? null : modelService.find(stored.getModel().getId());
        if (model == null || model.isDeleted()) {
            return null;
        }
        Outfit outfit = currentOutfit(stored, model);
        if (outfit != null && StringUtils.isNotBlank(outfit.description())) {
            // an outfit is described as a whole: the model's description, what never changes, and what she wears
            return StringUtils.isBlank(model.getAppearance()) ? outfit.description().strip()
                    : model.getAppearance().strip() + " " + outfit.description().strip();
        }
        return Appearance.of(model, BodyState.toggles(model, stored.partStateMap()));
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
        Switched switched = doSwitch(body, partId, stateId);
        List<QueuedEvent> events = new ArrayList<>();
        if (switched.stored().isActive() && switched.changed() && chatMessageService.hasAny(switched.stored().getJoi())) {
            switched.payload().addProperty("by", "user");
            events.add(queue(switched.stored().getJoi(), JoiEvents.PART_SWITCHED, switched.text(), switched.payload()));
        }
        return events;
    }

    @Override
    @CommonTx
    public String changePart(Body body, String partName, String stateName) throws Exception {
        Body stored = find(body.getId());
        Model model = model(stored);
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        List<DescribedPart> parts = model == null ? List.of() : DescribedPart.of(model, capabilities);
        DescribedPart part = parts.stream().filter(candidate -> candidate.name().equalsIgnoreCase(StringUtils.strip(partName)))
                .findFirst().orElse(null);
        if (part == null) {
            throw new IllegalArgumentException("You have no part called \"" + partName + "\". Your parts: "
                    + String.join(", ", parts.stream().map(DescribedPart::name).toList()));
        }
        DescribedPart.State state = part.states().stream().filter(candidate -> stateName(part, candidate).equalsIgnoreCase(StringUtils.strip(stateName)))
                .findFirst().orElse(null);
        if (state == null) {
            throw new IllegalArgumentException(part.name() + " can be: " + String.join(", ", part.states().stream()
                    .map(candidate -> stateName(part, candidate)).toList()));
        }
        DescribedPart.State before = BodyState.stateOf(model, part, stored.partStateMap());
        if (before != null && before.id().equals(state.id())) {
            return part.name() + " is already " + stateName(part, state) + ".";
        }
        // she did it herself: she is told what it looks like and what went with it, not again as an event
        return doSwitch(stored, part.id(), state.id()).text();
    }

    @Override
    @CommonTxReadOnly
    public List<PartOption> partOptions(Body body) throws Exception {
        Body stored = find(body.getId());
        Model model = model(stored);
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        List<PartOption> options = new ArrayList<>();
        if (model != null) {
            for (DescribedPart part : DescribedPart.of(model, capabilities)) {
                options.add(new PartOption(part.name(), part.states().stream().map(state -> stateName(part, state)).toList()));
            }
        }
        return options;
    }

    /** What the state is called in the words she is told: its name for a group, "shown" or "hidden" for a toggle. */
    private static String stateName(DescribedPart part, DescribedPart.State state) {
        return part.group() ? state.name() : DescribedPart.SHOWN.equals(state.id()) ? "shown" : "hidden";
    }

    /**
     * A part switched and stored.
     *
     * @param changed whether it was in another state before
     * @param text    the event as she is told it: how it looks, what went with it
     */
    private record Switched(Body stored, boolean changed, String text, JsonObject payload) {
    }

    private Switched doSwitch(Body body, String partId, String stateId) throws Exception {
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
        ToggleTree tree = ToggleTree.of(model, capabilities);
        Map<String, Boolean> seenBefore = tree.effective(BodyState.toggles(model, states));
        // what an outfit set for it is what it is switched to now
        part.toggles().forEach(toggle -> states.remove(BodyState.OUTFIT + toggle.id()));
        if (part.isDefault(state)) {
            states.remove(part.id());
        } else {
            states.put(part.id(), state.id());
        }
        stored.setPartStateMap(states);
        stored = save(stored);
        if (stored.isActive()) {
            bodyChanged(stored.getJoi());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("to", stored.getUuid());
        payload.addProperty("part", part.id());
        payload.addProperty("fromState", before == null ? null : before.id());
        payload.addProperty("toState", state.id());
        String stateName = stateName(part, state);
        // one event for the switch and what it took with it: the parts under this one, now out of sight or back in it
        Map<String, Boolean> seenAfter = tree.effective(BodyState.toggles(model, states));
        List<String> gone = new ArrayList<>();
        List<String> back = new ArrayList<>();
        for (DescribedPart other : DescribedPart.of(model, capabilities)) {
            if (!other.id().equals(part.id())) {
                boolean wasSeen = other.toggles().stream().anyMatch(toggle -> seenBefore.getOrDefault(toggle.id(), true));
                boolean isSeen = other.toggles().stream().anyMatch(toggle -> seenAfter.getOrDefault(toggle.id(), true));
                if (wasSeen && !isSeen) {
                    gone.add(other.name());
                } else if (!wasSeen && isSeen) {
                    back.add(other.name());
                }
            }
        }
        String hiddenBy = null;
        Map<String, Boolean> own = BodyState.toggles(model, states);
        // under a hidden part the switch is not seen, whatever state it was switched to
        if (part.toggles().stream().noneMatch(toggle -> seenAfter.getOrDefault(toggle.id(), true))) {
            for (ModelCapabilities.Toggle toggle : part.toggles()) {
                for (String above : tree.ancestors(toggle.id())) {
                    if (hiddenBy == null && !own.getOrDefault(above, true)) {
                        hiddenBy = capabilities.toggles().stream().filter(candidate -> candidate.id().equals(above))
                                .map(model::toggleName).findFirst().orElse(null);
                    }
                }
            }
        }
        payload.add("gone", names(gone));
        payload.add("back", names(back));
        String text = personaService.defaults().eventPartSwitched(part.name(), stateName, part.group(), part.sentence(model, state.id()),
                gone, back, hiddenBy);
        return new Switched(stored, before == null || !before.id().equals(state.id()), text, payload);
    }

    @Override
    @CommonTxReadOnly
    public PartRequest partRequest(Body body, String partId, String stateId) throws Exception {
        Body stored = find(body.getId());
        Model model = model(stored);
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        DescribedPart part = model == null ? null : DescribedPart.of(model, capabilities).stream()
                .filter(candidate -> candidate.id().equals(partId)).findFirst().orElse(null);
        DescribedPart.State state = part == null ? null : BodyState.state(part, stateId);
        if (state == null) {
            throw new IllegalArgumentException("no such part or state: " + partId + " / " + stateId);
        }
        DescribedPart.State before = BodyState.stateOf(model, part, stored.partStateMap());
        if (before != null && before.id().equals(state.id())) {
            return null;
        }
        String stateName = part.group() ? state.name() : DescribedPart.SHOWN.equals(state.id()) ? "shown" : "hidden";
        return new PartRequest(part.name(), stateName, part.group(), part.sentence(model, state.id()));
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
        Outfit wearing = model == null ? null : currentOutfit(active, model);
        List<String> outfits = active.outfitList().stream().filter(outfit -> wearing == null || !outfit.id().equals(wearing.id()))
                .map(Outfit::name).toList();
        BodyTemplateData data = new BodyTemplateData();
        data.setOutfit(wearing == null ? null : wearing.name());
        data.setOutfits(outfits.isEmpty() ? null : String.join(", ", outfits));
        data.setBodyName(name(active));
        data.setAppearance(StringUtils.defaultIfBlank(StringUtils.strip(appearance(active)), null));
        data.setExpressions(expressions.isEmpty() ? null : String.join(", ", expressions));
        data.setParts(parts.isEmpty() ? null : String.join(", ", parts));
        data.setOtherBodies(others.isEmpty() ? null : String.join(", ", others));
        return personaService.render(joiService.find(joi.getId()), JoiPromptField.BODY_PROMPT, data).strip();
    }

    private static JsonArray names(List<String> names) {
        JsonArray array = new JsonArray();
        names.forEach(array::add);
        return array;
    }

    private String name(Body body) {
        return StringUtils.defaultIfBlank(body.getName(), "unnamed");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // outfits
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    @CommonTxReadOnly
    public List<Outfit> outfits(Body body) throws Exception {
        return find(body.getId()).outfitList();
    }

    @Override
    @CommonTxReadOnly
    public Outfit currentOutfit(Body body) throws Exception {
        Body stored = find(body.getId());
        Model model = model(stored);
        return model == null ? null : currentOutfit(stored, model);
    }

    /** The outfit the toggles of the body's model are in the state of, null for none. */
    private Outfit currentOutfit(Body body, Model model) {
        ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
        Map<String, Boolean> state = BodyState.toggles(model, body.partStateMap());
        return body.outfitList().stream().filter(outfit -> outfit.matches(model, capabilities, state)).findFirst().orElse(null);
    }

    private static Outfit outfitByName(List<Outfit> outfits, String name) {
        return outfits.stream().filter(outfit -> outfit.name().equalsIgnoreCase(StringUtils.strip(name))).findFirst().orElse(null);
    }

    @Override
    @CommonTx
    public Outfit addOutfit(Body body, String name, String description, Map<String, Boolean> toggles) throws Exception {
        Body stored = find(body.getId());
        String named = StringUtils.strip(name);
        if (StringUtils.isBlank(named)) {
            throw new IllegalArgumentException("An outfit needs a name");
        }
        List<Outfit> outfits = stored.outfitList();
        if (outfitByName(outfits, named) != null) {
            throw new IllegalArgumentException("The body has an outfit called " + named + " already");
        }
        Model model = model(stored);
        Outfit outfit = Outfit.create(named, description, toggles != null ? toggles
                : model == null ? Map.of() : BodyState.shownNow(model, stored.partStateMap()));
        outfits.add(outfit);
        stored.setOutfitList(outfits);
        save(stored);
        return outfit;
    }

    @Override
    @CommonTx
    public Outfit saveOutfit(Body body, Outfit outfit) throws Exception {
        Body stored = find(body.getId());
        List<Outfit> outfits = stored.outfitList();
        int at = -1;
        for (int i = 0; i < outfits.size(); i++) {
            if (outfits.get(i).id().equals(outfit.id())) {
                at = i;
            }
        }
        if (at < 0) {
            throw new IllegalArgumentException("The body has no such outfit");
        }
        String named = StringUtils.strip(outfit.name());
        Outfit same = StringUtils.isBlank(named) ? null : outfitByName(outfits, named);
        if (StringUtils.isBlank(named) || same != null && !same.id().equals(outfit.id())) {
            throw new IllegalArgumentException(StringUtils.isBlank(named) ? "An outfit needs a name"
                    : "The body has an outfit called " + named + " already");
        }
        Outfit saved = outfit.named(named).described(outfit.description());
        outfits.set(at, saved);
        stored.setOutfitList(outfits);
        save(stored);
        return saved;
    }

    @Override
    @CommonTx
    public void deleteOutfit(Body body, String outfitId) throws Exception {
        Body stored = find(body.getId());
        List<Outfit> outfits = stored.outfitList();
        if (outfits.removeIf(outfit -> outfit.id().equals(outfitId))) {
            stored.setOutfitList(outfits);
            save(stored);
        }
    }

    @Override
    @CommonTx
    public List<QueuedEvent> wear(Body body, String outfitId) throws Exception {
        Body stored = find(body.getId());
        Outfit outfit = stored.outfitList().stream().filter(candidate -> candidate.id().equals(outfitId)).findFirst().orElse(null);
        if (outfit == null) {
            throw new IllegalArgumentException("The body has no such outfit");
        }
        Outfit before = currentOutfit(stored);
        stored.setPartStateMap(BodyState.wearing(outfit));
        stored = save(stored);
        if (stored.isActive()) {
            bodyChanged(stored.getJoi());
        }
        List<QueuedEvent> events = new ArrayList<>();
        if (stored.isActive() && (before == null || !before.id().equals(outfit.id())) && chatMessageService.hasAny(stored.getJoi())) {
            JsonObject payload = new JsonObject();
            payload.addProperty("to", stored.getUuid());
            payload.addProperty("outfit", outfit.id());
            payload.addProperty("by", "user");
            events.add(queue(stored.getJoi(), JoiEvents.OUTFIT_CHANGED,
                    personaService.defaults().eventOutfitChanged(outfit.name(), appearance(stored)), payload));
        }
        return events;
    }

    @Override
    @CommonTx
    public String wearOutfit(Body body, String outfitName) throws Exception {
        Body stored = find(body.getId());
        List<Outfit> outfits = stored.outfitList();
        Outfit outfit = outfitByName(outfits, outfitName);
        if (outfit == null) {
            throw new IllegalArgumentException(outfits.isEmpty() ? "This body has no outfits."
                    : "You have no outfit called \"" + outfitName + "\". Your outfits: " + String.join(", ", outfits.stream().map(Outfit::name).toList()));
        }
        Outfit before = currentOutfit(stored);
        if (before != null && before.id().equals(outfit.id())) {
            return "You wear " + outfit.name() + " already.";
        }
        stored.setPartStateMap(BodyState.wearing(outfit));
        stored = save(stored);
        if (stored.isActive()) {
            bodyChanged(stored.getJoi());
        }
        // she did it herself: she is told how she looks, not again as an event
        String look = appearance(stored);
        return "You put on " + outfit.name() + "." + (StringUtils.isBlank(look) ? "" : " How you look now: " + look.strip());
    }

    @Override
    @CommonTxReadOnly
    public String description(Body body) throws Exception {
        Body stored = find(body.getId());
        if (StringUtils.isNotBlank(stored.getDescription())) {
            return stored.getDescription().strip();
        }
        Model model = model(stored);
        return model == null || StringUtils.isBlank(model.getAppearance()) ? null : model.getAppearance().strip();
    }

    @Override
    @CommonTx
    public List<QueuedEvent> announce(Body body) throws Exception {
        Body stored = find(body.getId());
        Joi joi = stored.getJoi();
        Set<String> told = new java.util.HashSet<>(java.util.Arrays.asList(
                StringUtils.defaultString(stored.getAnnounced()).isBlank() ? new String[0] : stored.getAnnounced().split(",")));
        boolean model = model(stored) != null;
        boolean newBody = model && !told.contains("body");
        List<Outfit> fresh = stored.outfitList().stream().filter(outfit -> !told.contains(outfit.id())).toList();
        if (!newBody && fresh.isEmpty()) {
            return List.of();
        }
        if (newBody) {
            told.add("body");
        }
        fresh.forEach(outfit -> told.add(outfit.id()));
        stored.setAnnounced(String.join(",", told));
        save(stored);
        if (!chatMessageService.hasAny(joi)) {
            return List.of();
        }
        JoiDefaults defaults = personaService.defaults();
        List<String> lines = new ArrayList<>();
        JsonObject payload = new JsonObject();
        JsonArray bodies = new JsonArray();
        JsonArray outfitIds = new JsonArray();
        // the body she is in was told with her switch: this is for one she may move into
        if (newBody && !stored.isActive()) {
            lines.add(defaults.eventBodyAdded(name(stored), description(stored)));
            bodies.add(stored.getUuid());
        }
        if (!fresh.isEmpty()) {
            lines.add(defaults.eventOutfitsAdded(name(stored), stored.isActive(), fresh.stream().map(Outfit::name).toList(),
                    fresh.stream().map(outfit -> outfit.description() == null ? "" : outfit.description()).toList()));
            fresh.forEach(outfit -> outfitIds.add(outfit.id()));
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        payload.add("bodies", bodies);
        payload.add("outfits", outfitIds);
        return List.of(appendOrQueue(joi, JoiEvents.BODY_ADDED, String.join(" ", lines), payload));
    }

    /** The event of the kind that waits for the assistant gets the text added to it (and its payload's lists), else a new one is queued. */
    private QueuedEvent appendOrQueue(Joi joi, String kind, String text, JsonObject payload) throws Exception {
        QueuedEvent waiting = queuedEventService.findWaiting(joi).stream().filter(event -> kind.equals(event.getKind()))
                .reduce((first, second) -> second).orElse(null);
        if (waiting == null) {
            return queue(joi, kind, text, payload);
        }
        waiting.setText(waiting.getText() + "\n" + text);
        JsonObject merged = waiting.getPayload() == null ? new JsonObject()
                : com.google.gson.JsonParser.parseString(waiting.getPayload()).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : payload.entrySet()) {
            if (entry.getValue().isJsonArray() && merged.has(entry.getKey()) && merged.get(entry.getKey()).isJsonArray()) {
                merged.getAsJsonArray(entry.getKey()).addAll(entry.getValue().getAsJsonArray());
            } else {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        waiting.setPayload(merged.toString());
        return queuedEventService.save(waiting);
    }

    @Override
    @CommonTxReadOnly
    public List<BodyInfo> bodyInfos(Joi joi) throws Exception {
        List<BodyInfo> infos = new ArrayList<>();
        for (Body body : findForJoi(joi)) {
            Outfit wearing = body.isActive() ? currentOutfit(body) : null;
            List<OutfitInfo> outfits = body.outfitList().stream()
                    .map(outfit -> new OutfitInfo(outfit.name(), outfit.description(), wearing != null && wearing.id().equals(outfit.id()))).toList();
            infos.add(new BodyInfo(name(body), description(body), body.isActive(), outfits));
        }
        return infos;
    }

    @Override
    @CommonTx
    public String switchBody(Joi joi, String bodyName) throws Exception {
        List<Body> bodies = findForJoi(joi);
        Body target = bodies.stream().filter(body -> name(body).equalsIgnoreCase(StringUtils.strip(bodyName))).findFirst().orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("You have no body called \"" + bodyName + "\". Your bodies: "
                    + String.join(", ", bodies.stream().map(this::name).toList()));
        }
        if (target.isActive()) {
            return "You are in " + name(target) + " already.";
        }
        // she did it herself: no event, she is told what she looks like now
        for (Body other : getRepository().findByJoi(joi)) {
            boolean active = other.getId().equals(target.getId());
            if (other.isActive() != active) {
                other.setActive(active);
                save(other);
            }
        }
        Body now = find(target.getId());
        bodyChanged(joi);
        String look = appearance(now);
        Outfit wearing = currentOutfit(now);
        List<Outfit> outfits = now.outfitList();
        return "You moved into " + name(now) + "." + (StringUtils.isBlank(look) ? "" : " How you look now: " + look.strip())
                + (outfits.isEmpty() ? "" : " Its outfits: " + String.join(", ", outfits.stream()
                .map(outfit -> outfit.name() + (wearing != null && wearing.id().equals(outfit.id()) ? " (worn now)" : "")).toList()) + ".");
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
        stored = save(stored);
        if (stored.isActive()) {
            bodyChanged(stored.getJoi());
        }
        return stored;
    }

    private void forgetAnimations(Long resourceId) throws Exception {
        // a .joia is made again from the animations: the replaced one goes with its file
        modelService.discardDerived(resourceId);
    }
}
