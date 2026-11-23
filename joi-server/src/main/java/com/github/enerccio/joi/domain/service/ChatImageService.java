package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.impl.generation.dto.LLMAttachment;

import java.io.File;
import java.util.List;

/**
 * The images the user sends with a message (see {@code DESIGN.md} → Images in the chat). An upload is streamed into a file, read, turned
 * upright, scaled down to {@link com.github.enerccio.joi.domain.chat.ChatImages#MAX_SIDE} pixels and kept as a JPEG: a resource of the
 * user that no message uses yet. The message that takes it links it, and it goes with the message. What the model gets is the file, as it
 * is, in every prompt that holds the message.
 */
public interface ChatImageService {

    /** How many images go with one message. */
    int MAX_IMAGES = 4;

    /** An unused upload is deleted after this many minutes. */
    int PENDING_MINUTES = 60;

    /** An image as kept. */
    record Image(String id, String mimeType, long size, int width, int height) {
    }

    /** A new file in the user's folder of resources, for an upload being written ({@link #store} takes it). */
    File newUploadFile() throws Exception;

    /**
     * Keeps the image written to {@code upload}: reads it, turns it upright, scales it down and writes the JPEG as a resource of the
     * user. The upload file is deleted. Also deletes the user's uploads that no message used within {@link #PENDING_MINUTES}.
     *
     * @throws com.github.enerccio.joi.domain.chat.ChatImages.BadImageException when it is no image the server reads, or too large
     */
    Image store(File upload) throws Exception;

    /**
     * Checks the ids for a message about to be said: no more than {@link #MAX_IMAGES}, each the user's own, kept, and not used by a
     * message already.
     *
     * @return the ids, each once, in order
     * @throws IllegalArgumentException with what is wrong
     */
    List<String> check(List<String> ids) throws Exception;

    /** Links the images to the message (they are kept until it goes). */
    void attach(ChatMessage message, List<String> ids) throws Exception;

    /** The image of the user, null when there is none (or it is gone). */
    Image find(String uuid) throws Exception;

    /** The JPEG of the image, null when there is none. */
    File file(String uuid) throws Exception;

    /** The image as the model gets it, null when it is gone. */
    LLMAttachment attachment(String uuid) throws Exception;

    /** Deletes the images of the messages (soft, with them). */
    void deleteOf(List<ChatMessage> messages) throws Exception;

    /**
     * Whether she can look at images: her chat model takes images, or a model of the user that does can describe them for it.
     */
    boolean canSee(AI chatModel) throws Exception;

    /** The model that describes an image for a chat model that cannot see, null when none. */
    AI describer() throws Exception;

    /**
     * What the describing model says about the image, for a chat model that cannot see; written from her template
     * ({@code IMAGE_DESCRIPTION_PROMPT}). Waits for the model.
     *
     * @param text what the user wrote with the image, may be empty
     */
    String describe(Joi joi, String uuid, String text) throws Exception;
}
