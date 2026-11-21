package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** The exchanges of a conversation. */
public interface ChatMessageRepository extends ExtendableRepository<ChatMessage> {

    /** The exchanges (no notes of the system) sent in {@code [from, to)}, oldest first. */
    List<ChatMessage> findBetween(Joi joi, Date from, Date to) throws Exception;

    /** The last {@code limit} messages, notes of the system ({@code LOG}) too, oldest first. */
    List<ChatMessage> findLatest(Joi joi, int limit) throws Exception;

    /** The last {@code limit} exchanges (no notes of the system), oldest first: what the model is given. */
    List<ChatMessage> findLatestExchanges(Joi joi, int limit) throws Exception;

    /** The last {@code limit} exchanges (no notes of the system) sent at or after {@code since}, oldest first. */
    List<ChatMessage> findLatestExchangesSince(Joi joi, Date since, int limit) throws Exception;

    /**
     * The {@code limit} messages before the message (sent earlier, or at the same time and stored earlier), oldest
     * first: the earlier page of a transcript that shows the message first.
     */
    List<ChatMessage> findBefore(Joi joi, ChatMessage first, int limit) throws Exception;

    /** When each of the assistants was last talked to (notes of the system are not talking), by her id; one that never was is not in the map. */
    Map<Long, Date> findLastSentAt(Collection<Joi> jois) throws Exception;

    /** Whether the assistant has any exchange (a note of the system is none). */
    boolean hasAny(Joi joi) throws Exception;
}
