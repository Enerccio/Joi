package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.BodyProposal;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.templates.SelfImageTemplateData;
import com.github.enerccio.joi.domain.service.BodySelfImageService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.PersonaService;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BodySelfImageServiceImpl implements BodySelfImageService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BodySelfImageServiceImpl.class);

    /** The longest side of an image sent to the model; larger images of the user are scaled down. */
    static final int MAX_IMAGE_SIDE = 1568;
    /** How long the model may take. */
    static final long TIMEOUT_MINUTES = 10;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private JoiService joiService;

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
        Thread thread = new Thread(r, "body-self-image");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<Long, List<Listener>> listeners = new ConcurrentHashMap<>();

    @Override
    public AI model(Joi joi) throws Exception {
        List<AI> ais = aiService.findAllForUser();
        for (Long id : new Long[]{
                joi.getReasoningModel() != null ? joi.getReasoningModel() : personaService.getUserSetting().getReasoningModel(),
                joi.getChatModel() != null ? joi.getChatModel() : personaService.getUserSetting().getChatModel()}) {
            AI ai = id == null ? null : ais.stream().filter(a -> id.equals(a.getId())).findFirst().orElse(null);
            if (ai != null && ai.isMultimodal()) {
                return ai;
            }
        }
        return null;
    }

    @Override
    public void propose(Body body, Listener listener) throws Exception {
        Body own = bodyService.find(body.getId());
        if (own == null || own.getOwner() == null || !own.getOwner().getId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("not a body of the user");
        }
        if (own.getSnapshots().isEmpty() && own.getReferences().isEmpty()) {
            throw new IllegalStateException("The body has no snapshots nor images to look at");
        }
        if (model(joiService.find(own.getJoi().getId())) == null) {
            throw new IllegalStateException("She has no model that takes images: mark her deep reasoning or chat model as multimodal");
        }
        if (!running.add(own.getId())) {
            throw new IllegalStateException("A description of the body is being made already");
        }
        List<Listener> watching = listeners.computeIfAbsent(own.getId(), id -> new CopyOnWriteArrayList<>());
        if (listener != null) {
            watching.add(listener);
        }
        try {
            own.setProposalStatus(ProposalStatus.RUNNING);
            own.setProposalError(null);
            bodyService.save(own);
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
                Body finished = null;
                try {
                    finished = run(id);
                } catch (Throwable t) {
                    log.error("The self-image of body {} failed", id, t);
                } finally {
                    running.remove(id);
                    List<Listener> watched = listeners.remove(id);
                    if (finished != null && watched != null) {
                        for (Listener watcher : watched) {
                            try {
                                watcher.finished(finished);
                            } catch (Exception e) {
                                log.debug("A listener of the self-image failed", e);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                running.remove(id);
                listeners.remove(id);
                log.error("The self-image of body {} could not start", id, t);
            }
        });
    }

    /** Asks the model and stores its proposal, or the error, on the body. */
    Body run(Long id) throws Exception {
        Body body = bodyService.find(id);
        try {
            Joi joi = joiService.find(body.getJoi().getId());
            AI ai = model(joi);
            if (ai == null) {
                throw new IllegalStateException("She has no model that takes images");
            }
            Model model = body.getModel() == null ? null : modelService.find(body.getModel().getId());
            ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
            List<String> toggles = new ArrayList<>();
            List<ModelCapabilities.Toggle> known = new ArrayList<>();
            if (capabilities != null) {
                for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
                    known.add(toggle);
                    // numbered: a small model repeats a number more reliably than an id like "key:Body/Hide Blade L"
                    toggles.add(known.size() + " | " + (toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key())
                            + " | " + StringUtils.defaultIfBlank(model.getCapabilityNames().get(toggle.id()), toggle.defaultName())
                            + " | " + (toggle.shown() ? "shown" : "hidden"));
                }
            }

            LLMChatMessage request = LLMChatMessage.of(LLMRole.USER, null);
            List<String> shots = new ArrayList<>();
            for (Map.Entry<String, String> shot : body.getSnapshots().entrySet()) {
                File file = bodyService.imageFile(body, shot.getValue());
                if (file != null) {
                    request.addAttachment(image(file, shot.getKey() + ".jpg"));
                    shots.add(shot.getKey().replace('-', ' '));
                }
            }
            int references = 0;
            for (Map.Entry<String, String> reference : body.getReferences().entrySet()) {
                File file = bodyService.imageFile(body, reference.getKey());
                if (file != null) {
                    request.addAttachment(image(file, reference.getValue()));
                    references++;
                }
            }
            SelfImageTemplateData data = new SelfImageTemplateData();
            data.setBodyName(StringUtils.defaultIfBlank(body.getName(), "unnamed"));
            data.setSnapshots(shots.isEmpty() ? null : String.join(", ", shots));
            data.setReferences(references == 0 ? null : String.valueOf(references));
            data.setNotes(StringUtils.defaultIfBlank(body.getNotes(), null));
            data.setCurrentDescription(StringUtils.defaultIfBlank(body.getDescription(), null));
            data.setToggles(toggles.isEmpty() ? null : String.join("\n", toggles));
            request.setContent(personaService.render(joi, JoiPromptField.SELF_IMAGE_PROMPT, data));
            List<LLMChatMessage> payload = List.of(request);

            String answer = ask(ai, payload);
            BodyProposal parsed = BodyProposal.parse(answer, ai.getName(), AppClock.now());
            Map<String, String> names = new LinkedHashMap<>();
            for (Map.Entry<String, String> name : parsed.toggles().entrySet()) {
                ModelCapabilities.Toggle toggle = match(name.getKey(), known);
                if (toggle == null) {
                    log.info("The self-image of body {}: no toggle \"{}\" (named \"{}\")", id, name.getKey(), name.getValue());
                } else {
                    names.put(toggle.id(), name.getValue());
                }
            }
            body = bodyService.find(id);
            body.setProposal(new BodyProposal(parsed.description(), names, parsed.model(), parsed.at()).toJson());
            body.setProposalStatus(ProposalStatus.DONE);
            body.setProposalError(null);
        } catch (Exception e) {
            body = bodyService.find(id);
            body.setProposalStatus(ProposalStatus.FAILED);
            body.setProposalError(StringUtils.abbreviate(StringUtils.defaultIfBlank(ExceptionUtils.getRootCauseMessage(e), e.toString()), 2000));
        }
        return bodyService.save(body);
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
        ModelCapabilities.Toggle byKey = null;
        int keys = 0;
        for (ModelCapabilities.Toggle toggle : toggles) {
            String path = toggle.key() == null ? toggle.mesh() : toggle.mesh() + "/" + toggle.key();
            if (wanted.equals(normalized(toggle.id())) || wanted.equals(normalized(path))
                    || wanted.equals(normalized((toggle.key() == null ? "mesh:" : "key:") + path))) {
                return toggle;
            }
            if (toggle.key() != null && wanted.equals(normalized(toggle.key()))) {
                byKey = toggle;
                keys++;
            } else if (toggle.key() == null && wanted.equals(normalized(toggle.mesh()))) {
                byKey = byKey == null ? toggle : byKey;
                keys++;
            }
        }
        return keys == 1 ? byKey : null;
    }

    private static String normalized(String text) {
        return StringUtils.defaultString(text).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "")
                .replaceAll("^(toggle|#)", "");
    }

    /** The image as an attachment, a large one scaled down to {@link #MAX_IMAGE_SIDE} (as a JPEG). */
    static LLMAttachment image(File file, String name) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        String mimeType = mimeType(data);
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

    private static String mimeType(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') {
            return "image/png";
        }
        if (data.length >= 12 && data[0] == 'R' && data[8] == 'W') {
            return "image/webp";
        }
        return "image/jpeg";
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
    public boolean isRunning(Body body) {
        return body.getId() != null && running.contains(body.getId());
    }

    @Override
    public Runnable listen(Body body, Listener listener) {
        if (!isRunning(body)) {
            return () -> {
            };
        }
        List<Listener> watching = listeners.computeIfAbsent(body.getId(), id -> new CopyOnWriteArrayList<>());
        watching.add(listener);
        return () -> watching.remove(listener);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
