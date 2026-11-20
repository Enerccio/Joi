package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.ChatMessageRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** The exchanges of a conversation. */
public interface ChatMessageService extends ExtendableService<ChatMessage, ChatMessageRepository> {

    /** The exchanges (no notes of the system) sent in {@code [from, to)}, oldest first. */
    List<ChatMessage> findBetween(Joi joi, Date from, Date to) throws Exception;

    /** The last {@code limit} messages, notes of the system ({@code LOG}) too: the transcript. Oldest first. */
    List<ChatMessage> findLatest(Joi joi, int limit) throws Exception;

    /** The last {@code limit} exchanges, without the notes of the system: what the model is given. Oldest first. */
    List<ChatMessage> findLatestExchanges(Joi joi, int limit) throws Exception;

    /**
     * The {@code limit} messages before the message (sent earlier, or at the same time and stored earlier), oldest
     * first: the earlier page of a transcript that shows the message first.
     */
    List<ChatMessage> findBefore(Joi joi, ChatMessage first, int limit) throws Exception;

    /** When each of the assistants was last talked to, by her id; one that never was is not in the map. */
    Map<Long, Date> findLastSentAt(Collection<Joi> jois) throws Exception;

    /** Whether the assistant has any exchange (a note of the system is none). */
    boolean hasAny(Joi joi) throws Exception;

    /** A new file in the user's folder of resources for a clip being written (a part file, {@link #attachClip} names it). */
    File newClipFile() throws Exception;

    /**
     * Makes the file, written by {@link #newClipFile}, the message's clip: a resource of the user, linked to the message.
     *
     * @param extension {@code wav} or {@code mp3}
     * @param mime      its content type
     */
    ChatMessage attachClip(ChatMessage message, File clip, String extension, String mime) throws Exception;

    /** The file of the message's clip, null when it has none. */
    File clipFile(ChatMessage message) throws Exception;

    /** The content type of the message's clip, null when it has none. */
    String clipMime(ChatMessage message) throws Exception;

    /**
     * Deletes the assistant's messages (soft, with their prompts); an id that isn't one of her messages is skipped.
     *
     * @return how many were deleted
     */
    int deleteMessages(Joi joi, Collection<Long> ids) throws Exception;
}
