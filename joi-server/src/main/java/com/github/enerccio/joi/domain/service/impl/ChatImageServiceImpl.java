package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.chat.JoiInference;
import com.github.enerccio.marginalia.domain.service.InferenceSession;
import com.github.enerccio.joi.domain.chat.ChatImages;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.AppearanceService;
import com.github.enerccio.joi.domain.service.ChatImageService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ImageDescriptionTemplateData;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.InferenceService;
import com.github.enerccio.marginalia.domain.service.InferenceService.ChunkType;
import com.github.enerccio.marginalia.domain.service.InferenceService.InferenceRoundCallback;
import com.github.enerccio.marginalia.domain.service.InferenceService.RoundUsage;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMRole;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMToolCall;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.enerccio.marginalia.domain.service.impl.inference.tokenizer.EstimatedImageTokenizer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ChatImageServiceImpl implements ChatImageService {

    private static final Logger log = LoggerFactory.getLogger(ChatImageServiceImpl.class);

    /** The name of an upload's resource, and what tells it from the other resources of the user. */
    static final String NAME = "chat-image.jpg";

    /** The key of Joi's data about a chat image in its resource's attributes: its size in pixels, written when it is stored. */
    static final String ATTRIBUTES = "joi.chatImage";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";

    static final long DESCRIBE_TIMEOUT_MINUTES = 5;

    @Autowired
    private Configuration configuration;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private User currentUser;

    @Autowired
    private AppearanceService appearanceService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private InferenceServices inferenceServices;

    /** When the uploads of a user were swept last (the sweep reads all of the user's resources: once in a while is enough). */
    private final java.util.Map<Long, Long> swept = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public File newUploadFile() throws Exception {
        return resourceService.newUploadFile();
    }

    @Override
    @CommonTx
    public Image store(File upload) throws Exception {
        File folder = configuration.getResourcesFolder(currentUser).getCanonicalFile();
        if (!folder.equals(upload.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not in the folder of resources: " + upload);
        }
        // the scaled JPEG is a file of its own in the same folder; the platform moves it to the user's images and shares the file of
        // an image it already has
        File normalized = resourceService.newUploadFile();
        ChatImages.Info info;
        try {
            info = ChatImages.normalize(upload, normalized);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(normalized.toPath());
            throw e;
        } finally {
            Files.deleteIfExists(upload.toPath());
        }
        Resource resource = resourceService.saveFile(normalized, NAME, "image/jpeg");
        if (storedSize(resource) == null) {
            resource = keepSize(resource, info.width(), info.height());
        }
        sweep();
        return new Image(resource.getUuid(), "image/jpeg", resource.getSize(), info.width(), info.height());
    }

    /** Keeps the size of the image in pixels in its resource's attributes. */
    private Resource keepSize(Resource resource, int width, int height) throws Exception {
        JsonObject size = new JsonObject();
        size.addProperty(WIDTH, width);
        size.addProperty(HEIGHT, height);
        resource.getAttributes().add(ATTRIBUTES, size);
        return resourceService.save(resource);
    }

    /** The size of the image as kept on its resource, null when it is not (an image from before V2). */
    static int[] storedSize(Resource resource) {
        JsonElement size = resource.getAttributes().get(ATTRIBUTES);
        if (size == null || !size.isJsonObject()) {
            return null;
        }
        JsonObject object = size.getAsJsonObject();
        if (!object.has(WIDTH) || !object.has(HEIGHT)) {
            return null;
        }
        return new int[]{object.get(WIDTH).getAsInt(), object.get(HEIGHT).getAsInt()};
    }

    /** Deletes the user's uploads that nothing used for a while (at most once in {@value #PENDING_MINUTES} / 4 minutes). */
    private void sweep() {
        long now = System.currentTimeMillis();
        Long last = swept.put(currentUser.getId(), now);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(PENDING_MINUTES) / 4) {
            return;
        }
        try {
            resourceService.deleteUnlinked(NAME, new Date(now - TimeUnit.MINUTES.toMillis(PENDING_MINUTES)));
        } catch (Exception e) {
            log.debug("The sweep of the unused images failed", e);
        }
    }

    @Override
    @CommonTxReadOnly
    public List<String> check(List<String> ids) throws Exception {
        List<String> checked = List.copyOf(new LinkedHashSet<>(ids == null ? List.<String>of() : ids));
        if (checked.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("At most " + MAX_IMAGES + " images go with a message");
        }
        for (String id : checked) {
            Resource resource = own(id);
            if (resource == null) {
                throw new IllegalArgumentException("There is no image " + id + " (an image not used by a message within an hour is deleted)");
            }
            if (resource.getObjectId() != null) {
                throw new IllegalArgumentException("The image " + id + " belongs to a message already: send it again");
            }
        }
        return checked;
    }

    @Override
    @CommonTx
    public void attach(ChatMessage message, List<String> ids) throws Exception {
        for (String id : ids) {
            Resource resource = own(id);
            if (resource != null) {
                resourceService.link(resource, ChatMessage.class, message.getId());
            }
        }
    }

    private Resource own(String uuid) throws Exception {
        Resource resource = StringUtils.isBlank(uuid) ? null : resourceService.findForUser(uuid);
        return resource == null || resource.isDeleted() || !NAME.equals(resource.getOriginalName()) ? null : resource;
    }

    @Override
    @CommonTx
    public Image find(String uuid) throws Exception {
        Resource resource = own(uuid);
        File file = resource == null ? null : resourceService.getResourceFile(resource);
        if (file == null || !file.isFile()) {
            return null;
        }
        int[] size = storedSize(resource);
        if (size == null) {
            // an image stored before its size was kept on the resource: read from the header once, then kept
            size = ChatImages.size(file);
            if (size != null) {
                try {
                    keepSize(resource, size[0], size[1]);
                } catch (Exception e) {
                    log.debug("The size of the image {} could not be kept", uuid, e);
                }
            }
        }
        return new Image(resource.getUuid(), resource.getMimeType(), file.length(), size == null ? 0 : size[0], size == null ? 0 : size[1]);
    }

    @Override
    @CommonTx
    public long tokens(List<String> uuids) throws Exception {
        long tokens = 0;
        for (String uuid : uuids == null ? List.<String>of() : uuids) {
            Image image = find(uuid);
            tokens += image == null ? EstimatedImageTokenizer.UNKNOWN_TOKENS : EstimatedImageTokenizer.estimate(image.width(), image.height());
        }
        return tokens;
    }

    @Override
    @CommonTxReadOnly
    public File file(String uuid) throws Exception {
        Resource resource = own(uuid);
        File file = resource == null ? null : resourceService.getResourceFile(resource);
        return file != null && file.isFile() ? file : null;
    }

    @Override
    @CommonTxReadOnly
    public LLMAttachment attachment(String uuid) throws Exception {
        File file = file(uuid);
        if (file == null) {
            return null;
        }
        // the model's request holds the bytes (the platform's attachment is a byte array); a scaled image is a few hundred KB
        return LLMAttachment.of(LLMAttachment.Kind.IMAGE, "image/jpeg", uuid + ".jpg", Files.readAllBytes(file.toPath()));
    }

    @Override
    @CommonTx
    public void deleteOf(List<ChatMessage> messages) throws Exception {
        for (ChatMessage message : messages) {
            List<String> ids = new java.util.ArrayList<>(message.imageUuids());
            ids.addAll(message.toolImageUuids());
            for (String id : ids) {
                Resource resource = own(id);
                if (resource != null) {
                    resourceService.delete(resource, false);
                }
            }
        }
    }

    @Override
    public boolean canSee(AI chatModel) throws Exception {
        return (chatModel != null && chatModel.isMultimodal()) || describer() != null;
    }

    @Override
    public AI describer() throws Exception {
        return appearanceService.model();
    }

    @Override
    public String describe(Joi joi, String uuid, String text) throws Exception {
        AI ai = describer();
        if (ai == null) {
            throw new IllegalStateException("You have no model that takes images: mark your deep reasoning or chat model as multimodal");
        }
        LLMAttachment attachment = attachment(uuid);
        if (attachment == null) {
            throw new IllegalStateException("The image is gone");
        }
        ImageDescriptionTemplateData data = new ImageDescriptionTemplateData();
        data.setText(StringUtils.defaultIfBlank(text, null));
        LLMChatMessage request = LLMChatMessage.of(LLMRole.USER, personaService.render(joi, JoiPromptField.IMAGE_DESCRIPTION_PROMPT, data));
        request.addAttachment(attachment);
        InferenceService service = inferenceServices.forAIOrThrow(ai);
        CompletableFuture<String> done = new CompletableFuture<>();
        try (InferenceSession _ = JoiInference.open(JoiInference.DESCRIBE_IMAGE, joi, ai)) {
            service.round(List.of(request), null, null, new InferenceRoundCallback() {
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
        }
        try {
            String description = StringUtils.strip(done.get(DESCRIBE_TIMEOUT_MINUTES, TimeUnit.MINUTES));
            if (StringUtils.isBlank(description)) {
                throw new IllegalStateException("The model wrote no description of the image");
            }
            return description;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
