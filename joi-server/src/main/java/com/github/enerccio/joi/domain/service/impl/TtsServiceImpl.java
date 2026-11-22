package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.service.JoiAppSettingsService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.tts.*;
import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.http.BackendException;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.model.TtsSettings;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.model.impl.settings.BaseUserSetting;
import com.github.enerccio.marginalia.domain.service.GpuService;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.service.gpu.GpuException;
import com.github.enerccio.marginalia.domain.service.gpu.GpuLease;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestAttributes;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link TtsService} on the user setting (JSON under {@link TtsSettings#KEY}), the user's resources for the clips and the platform's
 * GPU locks. The calls to a server run on a virtual thread of their own, which does not need the user: what needs them (the
 * settings, the clip) is read before.
 */
public class TtsServiceImpl implements TtsService {

    /** The seed of a sample: the same text with the same voice sounds the same. */
    static final int SEED = 42;

    @Autowired
    private SettingService settingService;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private InferenceServices inferenceServices;

    @Autowired(required = false)
    private GpuService gpuService;

    @Autowired
    private JoiAppSettingsService appSettingsService;

    private final Gson gson = new Gson();

    @Override
    public TtsSettings getSettings() throws Exception {
        JsonObject attributes = settingService.getUserSetting().getAttributes();
        if (attributes != null && attributes.has(TtsSettings.KEY)) {
            TtsSettings settings = gson.fromJson(attributes.get(TtsSettings.KEY), TtsSettings.class);
            if (settings != null) {
                return settings;
            }
        }
        return new TtsSettings();
    }

    /** Reads the settings, changes them and stores them at once. */
    private void change(Consumer<TtsSettings> change) throws Exception {
        BaseUserSetting userSetting = settingService.getUserSetting();
        TtsSettings settings = getSettings();
        change.accept(settings);
        JsonObject attributes = userSetting.getAttributes();
        if (attributes == null) {
            attributes = new JsonObject();
            userSetting.setAttributes(attributes);
        }
        attributes.add(TtsSettings.KEY, gson.toJsonTree(settings));
        settingService.save(userSetting);
    }

    @Override
    public List<TtsModel> models() throws Exception {
        return byName(getSettings().getModels(), TtsModel::getName);
    }

    @Override
    public TtsModel findModel(String id) throws Exception {
        return getSettings().findModel(id);
    }

    @Override
    public void saveModel(TtsModel model) throws Exception {
        TtsModel stored = confined(model);
        change(settings -> settings.putModel(stored));
    }

    @Override
    public void deleteModel(String id) throws Exception {
        change(settings -> settings.getModels().removeIf(m -> m.getId().equals(id)));
    }

    @Override
    public TtsModel cloneModel(String id, String name) throws Exception {
        TtsModel original = findModel(id);
        if (original == null) {
            throw new IllegalArgumentException("No TTS model " + id);
        }
        TtsModel copy = original.duplicate(name);
        saveModel(copy);
        return copy;
    }

    @Override
    public List<Actor> actors() throws Exception {
        return byName(getSettings().getActors(), Actor::getName);
    }

    @Override
    public void saveActor(Actor actor) throws Exception {
        change(settings -> settings.putActor(actor));
    }

    @Override
    public void deleteActor(String id) throws Exception {
        change(settings -> settings.getActors().removeIf(a -> a.getId().equals(id)));
    }

    @Override
    public List<Clip> clips() throws Exception {
        List<Clip> clips = new ArrayList<>();
        for (Resource resource : resourceService.findAllForUser()) {
            String name = resource.getOriginalName() == null ? "" : resource.getOriginalName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean audio = (resource.getMimeType() != null && resource.getMimeType().startsWith("audio/")) || lower.endsWith(".wav")
                    || lower.endsWith(".mp3");
            if (audio) {
                clips.add(new Clip(resource.getUuid(), name));
            }
        }
        return byName(clips, Clip::name);
    }

    @Override
    public Clip uploadClip(String fileName, byte[] content, String contentType) throws Exception {
        Resource resource = resourceService.upload(fileName, content, contentType);
        return new Clip(resource.getUuid(), fileName);
    }

    @Override
    public String clipText(String clipUuid) throws Exception {
        return getSettings().getClipTexts().get(clipUuid);
    }

    @Override
    public void saveClipText(String clipUuid, String text) throws Exception {
        change(settings -> {
            if (StringUtils.isBlank(text)) {
                settings.getClipTexts().remove(clipUuid);
            } else {
                settings.getClipTexts().put(clipUuid, text.strip());
            }
        });
    }

    @Override
    public String protect(String secret) {
        return StringUtils.isBlank(secret) ? null : inferenceServices.protectSecret(secret);
    }

    private String reveal(String protectedText) {
        return StringUtils.isBlank(protectedText) ? null : inferenceServices.revealSecret(protectedText);
    }

    @Override
    public SpeechBackend createBackend(TtsModel model) {
        model = confined(model);
        String key = reveal(model.getProtectedApiKey());
        return model.isHiggs() ? new HiggsComfyBackend(model, key) : new ChatterboxBackend(model, key);
    }

    @Override
    public CompletableFuture<ServerCheck.Result> test(TtsModel asGiven) {
        TtsModel model = confined(asGiven);
        String key = reveal(model.getProtectedApiKey());
        return inBackground(() -> {
            try (GpuLease _ = lease(model)) {
                return ServerCheck.checkWithKey(model, key);
            }
        });
    }

    @Override
    public CompletableFuture<byte[]> speak(TtsModel model, String voice, String text) throws Exception {
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("There is nothing to say");
        }
        VoiceRef ref = VoiceRef.parse(voice);
        byte[] clip = null;
        String clipText = null;
        if (ref.kind() == VoiceRef.Kind.CLIP) {
            Resource resource = resourceService.findForUser(ref.id());
            if (resource == null) {
                throw new IllegalArgumentException("The clip is gone");
            }
            clip = resourceService.getResourceData(resource);
            clipText = clipText(ref.id());
        }
        SpeechBackend backend = createBackend(model);
        byte[] clipAudio = clip;
        String words = clipText;
        return inBackground(() -> {
            try (GpuLease _ = lease(model)) {
                SpeechRequest.VoiceMode mode = clipAudio == null ? SpeechRequest.VoiceMode.PREDEFINED : SpeechRequest.VoiceMode.CLONE;
                String name = clipAudio == null ? ref.id() : backend.ensureReference(clipAudio, AudioKind.of(clipAudio).extension());
                SpeechRequest request = SpeechRequest.of(model, text.strip(), mode, name).withSeed(SEED);
                if (model.isSendClipText() && words != null) {
                    request = request.withReferenceText(words);
                }
                return backend.synthesize(request, () -> false);
            }
        });
    }

    /**
     * The model as the current user may use it: marked local only when the administrator lets them have the server call
     * its own network ({@link JoiAppSettingsService#localServersAllowed}); otherwise the private addresses are refused.
     * When that can't be told, it is not allowed.
     */
    private TtsModel confined(TtsModel model) {
        if (model == null || !model.isLocal()) {
            return model;
        }
        boolean allowed;
        try {
            allowed = appSettingsService.localServersAllowed();
        } catch (Exception e) {
            allowed = false;
        }
        return allowed ? model : model.withoutLocal();
    }

    /** The GPU lock of the model, taken with {@code {{model}}} for its steps; none when it has no lock. */
    private GpuLease lease(TtsModel model) throws BackendException {
        if (gpuService == null || model.getGpuLock() == null) {
            return GpuLease.NONE;
        }
        try {
            try {
                return gpuService.acquire(model.getGpuLock(), GpuService.model(model.getModel()), null);
            } catch (GpuException e) {
                if (!connectionDropped(e)) {
                    throw e;
                }
                // the platform's HTTP client keeps its connections open and the proxy in front of the speech server may close one meanwhile
                // ("header parser received no bytes"); the JDK does not send a POST again. A failed acquire gave the lock back and left the
                // GPU unknown, so the steps just run again, on a new connection
                TtsLog.get().info("A step of the GPU lock of {} lost its connection ({}), once more", model.getModel(), e.getMessage());
                return gpuService.acquire(model.getGpuLock(), GpuService.model(model.getModel()), null);
            }
        } catch (GpuException e) {
            throw new BackendException(e.getMessage(), e);
        }
    }

    /** A step failed because the connection broke (a connection that was closed, reset or never answered), not because the server answered or time ran out. */
    static boolean connectionDropped(GpuException e) {
        if (e.isCancelled()) {
            return false;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return false;
            }
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> CompletableFuture<T> inBackground(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        // the thread works for the user of the caller: the job of the call is theirs (the session scope is not active on it)
        RequestAttributes attributes = ThreadCopyRequestAttributes.create();
        Thread.ofVirtual().name("joi-tts").start(() -> {
            try (InRequestScope _ = new InRequestScope(attributes)) {
                result.complete(work.run());
            } catch (Exception | Error e) {
                TtsLog.get().warn("A call to a speech server failed: {}", e.getMessage());
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static <T> List<T> byName(List<T> items, Function<T, String> name) {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(item -> name.apply(item) == null ? "" : name.apply(item), collator));
        return sorted;
    }
}
