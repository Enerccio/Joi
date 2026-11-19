package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.events.JoiEvents;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.templates.BodyTemplateData;
import com.github.enerccio.joi.domain.repository.BodyRepository;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.QueuedEventService;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.github.enerccio.marginalia.utils.AppClock;
import com.google.gson.JsonObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
    private ResourceService resourceService;

    @Autowired
    private Configuration configuration;

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
                        to == null ? null : name(to), to == null ? null : to.getDescription()),
                from == null ? null : from.getUuid(), to == null ? null : to.getUuid()));
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

    @Override
    @CommonTxReadOnly
    public String modelHash(Body body) throws Exception {
        Model model = body.getModel() == null ? null : modelService.find(body.getModel().getId());
        return model == null || model.isDeleted() ? null : modelService.bodyHash(model);
    }

    @Override
    @CommonTx
    public Body storeSnapshots(Body body, Map<String, byte[]> shots) throws Exception {
        Body stored = find(body.getId());
        File folder = modelService.uploadFolder();
        Map<String, String> before = new LinkedHashMap<>(stored.getSnapshots());
        Map<String, String> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> shot : shots.entrySet()) {
            String name = shot.getKey().replaceAll("[^a-z0-9-]", "");
            if (name.isEmpty() || ImageType.of(shot.getValue()) != ImageType.JPEG) {
                throw new IllegalArgumentException("not a snapshot: " + shot.getKey());
            }
            File file = ModelFiles.uploadFile(folder, name + ".jpg");
            Files.write(file.toPath(), shot.getValue());
            Resource resource = store(file, name + ".jpg", "image/jpeg");
            resourceService.link(resource, Body.class, stored.getId());
            snapshots.put(name, String.valueOf(resource.getId()));
        }
        stored.setSnapshots(snapshots);
        stored.setSnapshotsHash(modelHash(stored));
        stored.setSnapshotsAt(AppClock.now());
        Body saved = save(stored);
        forget(before.values());
        return saved;
    }

    @Override
    @CommonTx
    public Body addReference(Body body, File file, String fileName) throws Exception {
        File folder = modelService.uploadFolder().getCanonicalFile();
        if (!folder.equals(file.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + file);
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            head = in.readNBytes(16);
        }
        ImageType type = ImageType.of(head);
        if (type == null) {
            Files.deleteIfExists(file.toPath());
            throw new IllegalArgumentException("not an image: " + fileName);
        }
        Body stored = find(body.getId());
        Resource resource = store(file, StringUtils.defaultIfBlank(fileName, file.getName()), type.mimeType);
        resourceService.link(resource, Body.class, stored.getId());
        stored.getReferences().put(String.valueOf(resource.getId()), StringUtils.defaultIfBlank(fileName, file.getName()));
        return save(stored);
    }

    @Override
    @CommonTx
    public Body removeReference(Body body, String resourceId) throws Exception {
        Body stored = find(body.getId());
        if (stored.getReferences().remove(resourceId) != null) {
            forget(List.of(resourceId));
        }
        return save(stored);
    }

    @Override
    @CommonTxReadOnly
    public File imageFile(Body body, String resourceId) throws Exception {
        if (resourceId == null || !(body.getSnapshots().containsValue(resourceId) || body.getReferences().containsKey(resourceId))) {
            return null;
        }
        Resource resource = resourceService.find(Long.valueOf(resourceId));
        return resource == null || resource.isDeleted() || resource.getOwner() == null
                || !resource.getOwner().getId().equals(currentUser.getId()) ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTx
    public List<QueuedEvent> applyProposal(Body body, String description, Map<String, String> toggleNames) throws Exception {
        Body stored = find(body.getId());
        String before = stored.getDescription();
        stored.setDescription(StringUtils.defaultIfBlank(StringUtils.strip(description), null));
        stored.setDescribedHash(stored.getSnapshotsHash());
        stored.setProposal(null);
        stored.setProposalStatus(null);
        stored.setProposalError(null);
        Body saved = save(stored);
        Model model = saved.getModel() == null ? null : modelService.find(saved.getModel().getId());
        if (model != null && !toggleNames.isEmpty()) {
            ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
            for (ModelCapabilities.Toggle toggle : capabilities == null ? List.<ModelCapabilities.Toggle>of() : capabilities.toggles()) {
                String name = StringUtils.strip(toggleNames.get(toggle.id()));
                if (StringUtils.isBlank(name) || name.equals(toggle.defaultName())) {
                    model.getCapabilityNames().remove(toggle.id());
                } else if (toggleNames.containsKey(toggle.id())) {
                    model.getCapabilityNames().put(toggle.id(), name);
                }
            }
            modelService.save(model);
        }
        List<QueuedEvent> events = new ArrayList<>();
        if (saved.isActive() && saved.getDescription() != null && !Objects.equals(before, saved.getDescription())
                && chatMessageService.hasAny(saved.getJoi())) {
            events.add(queue(saved.getJoi(), JoiEvents.BODY_DESCRIBED,
                    personaService.defaults().eventBodyDescribed(name(saved), saved.getDescription()), null, saved.getUuid()));
        }
        return events;
    }

    @Override
    @CommonTx
    public Body discardProposal(Body body) throws Exception {
        Body stored = find(body.getId());
        stored.setProposal(null);
        stored.setProposalStatus(null);
        stored.setProposalError(null);
        return save(stored);
    }

    /**
     * A resource of the current user for an image in their folder of resources: the file moves to their folder of
     * images, where the platform keeps the files of images.
     */
    private Resource store(File file, String originalName, String contentType) throws Exception {
        File images = configuration.getImagesFolder(currentUser);
        Files.createDirectories(images.toPath());
        File moved = new File(images, file.getName());
        Files.move(file.toPath(), moved.toPath(), StandardCopyOption.REPLACE_EXISTING);
        file = moved;
        Resource resource = new Resource();
        resource.setOriginalName(originalName);
        resource.setMimeType(contentType);
        resource.setPath(file.getName());
        resource.setSize(file.length());
        try (InputStream in = Files.newInputStream(file.toPath())) {
            resource.setHash(DigestUtils.sha256Hex(in));
        }
        return resourceService.save(resource);
    }

    /** Deletes the resources (by id) the body no longer has. */
    private void forget(Collection<String> resourceIds) throws Exception {
        List<String> uuids = new ArrayList<>();
        for (String id : resourceIds) {
            Resource resource = resourceService.find(Long.valueOf(id));
            if (resource != null && !resource.isDeleted()) {
                uuids.add(resource.getUuid());
            }
        }
        if (!uuids.isEmpty()) {
            resourceService.softDelete(uuids);
        }
    }

    /** The images a body takes, by their first bytes. */
    enum ImageType {
        PNG("image/png"), JPEG("image/jpeg"), WEBP("image/webp");

        final String mimeType;

        ImageType(String mimeType) {
            this.mimeType = mimeType;
        }

        static ImageType of(byte[] head) {
            if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return PNG;
            }
            if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return WEBP;
            }
            return null;
        }
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
            for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
                String name = StringUtils.defaultIfBlank(model.getCapabilityNames().get(toggle.id()), toggle.defaultName());
                parts.add(name + (toggle.shown() ? " (shown)" : " (hidden)"));
            }
        }
        List<String> others = bodies.stream().filter(body -> !body.isActive()).map(this::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        BodyTemplateData data = new BodyTemplateData();
        data.setBodyName(name(active));
        data.setAppearance(StringUtils.defaultIfBlank(StringUtils.strip(active.getDescription()), null));
        data.setExpressions(expressions.isEmpty() ? null : String.join(", ", expressions));
        data.setParts(parts.isEmpty() ? null : String.join(", ", parts));
        data.setOtherBodies(others.isEmpty() ? null : String.join(", ", others));
        return personaService.render(joiService.find(joi.getId()), JoiPromptField.BODY_PROMPT, data).strip();
    }

    private String name(Body body) {
        return StringUtils.defaultIfBlank(body.getName(), "unnamed");
    }
}
