package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.AppearanceProposal;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.AppearanceService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.PartImageTemplateData;
import com.github.enerccio.joi.domain.templates.SelfImageTemplateData;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.utils.AppClock;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppearanceServiceImpl implements AppearanceService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppearanceServiceImpl.class);

    /** The longest side of an image sent to the model; larger images of the user are scaled down. */
    static final int MAX_IMAGE_SIDE = 1568;
    /** How long the model may take for one request. */
    static final long TIMEOUT_MINUTES = 10;
    /** The prefix of the snapshots of a toggle: {@code part:<toggle id>:shown} / {@code :hidden}. */
    public static final String PART_SHOT = "part:";

    @Autowired
    private ModelService modelService;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private AIService aiService;

    @Autowired
    private InferenceServices inferenceServices;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "model-appearance");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<Long, List<Listener>> listeners = new ConcurrentHashMap<>();

    /** The name of the snapshot of a toggle in a state. */
    public static String partShot(String toggleId, boolean shown) {
        return PART_SHOT + toggleId + (shown ? ":shown" : ":hidden");
    }

    @Override
    public AI model() throws Exception {
        List<AI> ais = aiService.findAllForUser();
        for (Long id : new Long[]{personaService.getUserSetting().getReasoningModel(), personaService.getUserSetting().getChatModel()}) {
            AI ai = id == null ? null : ais.stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
            if (ai != null && ai.isMultimodal()) {
                return ai;
            }
        }
        return null;
    }

    @Override
    public void propose(Model model, Listener listener) throws Exception {
        Model own = modelService.find(model.getId());
        if (own == null || own.getOwner() == null || !own.getOwner().getId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("not a model of the user");
        }
        if (own.getSnapshots().isEmpty() && own.getReferences().isEmpty()) {
            throw new IllegalStateException("The model has no snapshots nor images to look at");
        }
        if (model() == null) {
            throw new IllegalStateException("You have no model that takes images: mark your deep reasoning or chat model as multimodal");
        }
        if (!running.add(own.getId())) {
            throw new IllegalStateException("A description of the model is being made already");
        }
        List<Listener> watching = listeners.computeIfAbsent(own.getId(), id -> new CopyOnWriteArrayList<>());
        if (listener != null) {
            watching.add(listener);
        }
        try {
            own.setProposalStatus(ProposalStatus.RUNNING);
            own.setProposalError(null);
            own.setProposalProgress(null);
            modelService.save(own);
        } catch (Exception e) {
            running.remove(own.getId());
            listeners.remove(own.getId());
            throw e;
        }
        User user = userService.find(currentUser.getId());
        Long id = own.getId();
        executor.submit(() -> {
            try (ApiRequestAttributes attributes = new ApiRequestAttributes(user, new SessionPoint());
                 InRequestScope _ = new InRequestScope(attributes)) {
                Model finished = null;
                try {
                    finished = run(id);
                } catch (Throwable t) {
                    log.error("The appearance of model {} failed", id, t);
                } finally {
                    running.remove(id);
                    List<Listener> watched = listeners.remove(id);
                    if (finished != null && watched != null) {
                        for (Listener watcher : watched) {
                            try {
                                watcher.finished(finished);
                            } catch (Exception e) {
                                log.debug("A listener of the appearance failed", e);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                running.remove(id);
                listeners.remove(id);
                log.error("The appearance of model {} could not start", id, t);
            }
        });
    }

    /** Asks for the whole model, then for each toggle described apart, and stores the proposal, or the error. */
    Model run(Long id) throws Exception {
        Model model = modelService.find(id);
        try {
            AI ai = model();
            if (ai == null) {
                throw new IllegalStateException("You have no model that takes images");
            }
            ModelCapabilities capabilities = ModelCapabilities.fromJson(model.getCapabilities());
            List<ModelCapabilities.Toggle> toggles = capabilities == null ? List.of() : capabilities.toggles();
            List<String> described = model.describedToggles(capabilities);
            int total = 1 + described.size();
            progress(id, 0, total);

            List<String> lines = new ArrayList<>();
            for (ModelCapabilities.Toggle toggle : toggles) {
                // numbered: a small model repeats a number more reliably than an id like "key:Body/Hide Blade L"
                lines.add((lines.size() + 1) + " | " + source(toggle) + " | " + name(model, toggle) + " | " + (toggle.shown() ? "shown" : "hidden"));
            }
            LLMChatMessage request = LLMChatMessage.of(LLMRole.USER, null);
            List<String> shots = new ArrayList<>();
            for (Map.Entry<String, String> shot : model.getSnapshots().entrySet()) {
                File file = shot.getKey().startsWith(PART_SHOT) ? null : modelService.imageFile(model, shot.getValue());
                if (file != null) {
                    request.addAttachment(image(file, shot.getKey() + ".jpg"));
                    shots.add(shot.getKey().replace('-', ' '));
                }
            }
            int references = 0;
            for (Map.Entry<String, String> reference : model.getReferences().entrySet()) {
                File file = modelService.imageFile(model, reference.getKey());
                if (file != null) {
                    request.addAttachment(image(file, reference.getValue()));
                    references++;
                }
            }
            SelfImageTemplateData data = new SelfImageTemplateData();
            data.setModelName(StringUtils.defaultIfBlank(model.getName(), "unnamed"));
            data.setSnapshots(shots.isEmpty() ? null : String.join(", ", shots));
            data.setReferences(references == 0 ? null : String.valueOf(references));
            data.setNotes(StringUtils.defaultIfBlank(model.getNotes(), null));
            data.setCurrentDescription(StringUtils.defaultIfBlank(model.getAppearance(), null));
            data.setToggles(lines.isEmpty() ? null : String.join("\n", lines));
            Model asked = model;
            List<String> partNames = toggles.stream().filter(t -> described.contains(t.id())).map(t -> name(asked, t)).toList();
            data.setParts(partNames.isEmpty() ? null : String.join(", ", partNames));
            request.setContent(personaService.render(JoiPromptField.SELF_IMAGE_PROMPT, data));

            AppearanceProposal whole = AppearanceProposal.parse(ask(ai, List.of(request)), ai.getName(), AppClock.now());
            Map<String, String> names = new LinkedHashMap<>();
            for (Map.Entry<String, String> name : whole.toggles().entrySet()) {
                ModelCapabilities.Toggle toggle = match(name.getKey(), toggles);
                if (toggle == null) {
                    log.info("The appearance of model {}: no toggle \"{}\" (named \"{}\")", id, name.getKey(), name.getValue());
                } else {
                    names.put(toggle.id(), name.getValue());
                }
            }

            Map<String, AppearanceProposal.Part> parts = new LinkedHashMap<>();
            int step = 1;
            for (ModelCapabilities.Toggle toggle : toggles) {
                if (!described.contains(toggle.id())) {
                    continue;
                }
                progress(id, step++, total);
                File shown = modelService.imageFile(model, model.getSnapshots().get(partShot(toggle.id(), true)));
                File hidden = modelService.imageFile(model, model.getSnapshots().get(partShot(toggle.id(), false)));
                if (shown == null || hidden == null) {
                    log.info("The appearance of model {}: no close-ups of {}", id, toggle.id());
                    continue;
                }
                PartImageTemplateData part = new PartImageTemplateData();
                part.setModelName(data.getModelName());
                part.setPartName(StringUtils.defaultIfBlank(names.get(toggle.id()), name(model, toggle)));
                part.setPartSource(source(toggle));
                part.setAppearance(whole.description());
                part.setNotes(data.getNotes());
                LLMChatMessage partRequest = LLMChatMessage.of(LLMRole.USER, personaService.render(JoiPromptField.PART_IMAGE_PROMPT, part));
                partRequest.addAttachment(image(shown, "shown.jpg"));
                partRequest.addAttachment(image(hidden, "hidden.jpg"));
                try {
                    parts.put(toggle.id(), AppearanceProposal.parsePart(ask(ai, List.of(partRequest))));
                } catch (IllegalArgumentException e) {
                    log.info("The appearance of model {}: {} was not described: {}", id, toggle.id(), e.getMessage());
                }
            }

            model = modelService.find(id);
            model.setProposal(whole.with(names, parts).toJson());
            model.setProposalStatus(ProposalStatus.DONE);
            model.setProposalError(null);
        } catch (Exception e) {
            model = modelService.find(id);
            model.setProposalStatus(ProposalStatus.FAILED);
            model.setProposalError(StringUtils.abbreviate(StringUtils.defaultIfBlank(ExceptionUtils.getRootCauseMessage(e), e.toString()), 2000));
        }
        model.setProposalProgress(null);
        return modelService.save(model);
    }

    private void progress(Long id, int done, int total) throws Exception {
        Model model = modelService.find(id);
        model.setProposalProgress(done + " / " + total);
        Model saved = modelService.save(model);
        for (Listener listener : listeners.getOrDefault(id, List.of())) {
            try {
                listener.progress(saved);
            } catch (Exception e) {
                log.debug("A listener of the appearance failed", e);
            }
        }
    }

    private static String source(ModelCapabilities.Toggle toggle) {
        return toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key();
    }

    private static String name(Model model, ModelCapabilities.Toggle toggle) {
        return StringUtils.defaultIfBlank(model.getCapabilityNames().get(toggle.id()), toggle.defaultName());
    }

    /**
     * The toggle the model meant: its number in the request, its id, or its "mesh / key" (case and spacing aside); a
     * key alone when only one mesh has it. Null when none fits.
     */
    static ModelCapabilities.Toggle match(String reference, List<ModelCapabilities.Toggle> toggles) {
        String wanted = normalized(reference);
        if (wanted.matches("\\d+")) {
            int index = Integer.parseInt(wanted) - 1;
            return index >= 0 && index < toggles.size() ? toggles.get(index) : null;
        }
        ModelCapabilities.Toggle byName = null;
        int found = 0;
        for (ModelCapabilities.Toggle toggle : toggles) {
            String path = toggle.key() == null ? toggle.mesh() : toggle.mesh() + "/" + toggle.key();
            if (wanted.equals(normalized(toggle.id())) || wanted.equals(normalized(path))) {
                return toggle;
            }
            if (wanted.equals(normalized(toggle.key() == null ? toggle.mesh() : toggle.key()))) {
                byName = toggle;
                found++;
            }
        }
        return found == 1 ? byName : null;
    }

    private static String normalized(String text) {
        return StringUtils.defaultString(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("^(toggle|#)", "");
    }

    /** The image as an attachment, a large one scaled down to {@link #MAX_IMAGE_SIDE} (as a JPEG). */
    static LLMAttachment image(File file, String name) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        String mimeType = data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' ? "image/png"
                : data.length >= 12 && data[0] == 'R' && data[8] == 'W' ? "image/webp" : "image/jpeg";
        BufferedImage image = mimeType.equals("image/webp") ? null : ImageIO.read(file);
        if (image != null && Math.max(image.getWidth(), image.getHeight()) > MAX_IMAGE_SIDE) {
            double scale = (double) MAX_IMAGE_SIDE / Math.max(image.getWidth(), image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpg", out);
            data = out.toByteArray();
            mimeType = "image/jpeg";
        }
        LLMAttachment attachment = new LLMAttachment();
        attachment.setKind(LLMAttachment.Kind.IMAGE);
        attachment.setMimeType(mimeType);
        attachment.setName(name);
        attachment.setData(data);
        return attachment;
    }

    private String ask(AI ai, List<LLMChatMessage> payload) throws Exception {
        InferenceService service = inferenceServices.forAIOrThrow(ai);
        CompletableFuture<String> done = new CompletableFuture<>();
        service.round(payload, null, null, new InferenceRoundCallback() {
            @Override
            public void onChunk(ChunkType chunkType, String chunk) {
            }

            @Override
            public void onRound(String text, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(StringUtils.defaultString(text));
            }

            @Override
            public void onRound(String text, String reasoning, List<LLMToolCall> calls, RoundUsage usage) {
                done.complete(StringUtils.defaultString(text));
            }

            @Override
            public void onCancel() {
                done.completeExceptionally(new IllegalStateException("The model stopped"));
            }

            @Override
            public void onError(Throwable exception) {
                done.completeExceptionally(exception);
            }
        });
        try {
            return done.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    @Override
    public boolean isRunning(Model model) {
        return model.getId() != null && running.contains(model.getId());
    }

    @Override
    public Runnable listen(Model model, Listener listener) {
        if (!isRunning(model)) {
            return () -> {
            };
        }
        List<Listener> watching = listeners.computeIfAbsent(model.getId(), id -> new CopyOnWriteArrayList<>());
        watching.add(listener);
        return () -> watching.remove(listener);
    }

    @Override
    public List<QueuedEvent> apply(Model model, String description, Map<String, String> toggleNames,
                                   Map<String, AppearanceProposal.Part> parts) throws Exception {
        Model stored = modelService.find(model.getId());
        stored.setAppearance(StringUtils.defaultIfBlank(StringUtils.strip(description), null));
        ModelCapabilities capabilities = ModelCapabilities.fromJson(stored.getCapabilities());
        for (ModelCapabilities.Toggle toggle : capabilities == null ? List.<ModelCapabilities.Toggle>of() : capabilities.toggles()) {
            if (toggleNames.containsKey(toggle.id())) {
                String name = StringUtils.strip(toggleNames.get(toggle.id()));
                if (StringUtils.isBlank(name) || name.equals(toggle.defaultName())) {
                    stored.getCapabilityNames().remove(toggle.id());
                } else {
                    stored.getCapabilityNames().put(toggle.id(), name);
                }
            }
            AppearanceProposal.Part part = parts.get(toggle.id());
            if (part != null) {
                put(stored.getPartShown(), toggle.id(), part.shown());
                put(stored.getPartHidden(), toggle.id(), part.hidden());
            }
        }
        stored.setDescribedHash(stored.getSnapshotsHash());
        stored.setProposal(null);
        stored.setProposalStatus(null);
        stored.setProposalError(null);
        Model saved = modelService.save(stored);
        return bodyService.modelDescribed(saved);
    }

    private static void put(Map<String, String> sentences, String id, String sentence) {
        if (StringUtils.isBlank(sentence)) {
            sentences.remove(id);
        } else {
            sentences.put(id, sentence.strip());
        }
    }

    @Override
    public Model discard(Model model) throws Exception {
        Model stored = modelService.find(model.getId());
        stored.setProposal(null);
        stored.setProposalStatus(null);
        stored.setProposalError(null);
        return modelService.save(stored);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
