package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.ChatMessageRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.*;

public class JpaChatMessageRepository extends JpaExtendableRepository<ChatMessage> implements ChatMessageRepository {

    @Override
    protected Class<ChatMessage> getEntityClass() {
        return ChatMessage.class;
    }

    @Override
    public List<ChatMessage> findBetween(Joi joi, Date from, Date to) throws Exception {
        return entityManager.createQuery("SELECT m FROM ChatMessage m WHERE m.deleted = false AND m.joi = :joi AND m.type <> :log AND m.sentAt >= :from AND m.sentAt < :to ORDER BY m.sentAt ASC, m.id ASC", ChatMessage.class)
                .setParameter("log", ChatMessageType.LOG).setParameter("joi", joi).setParameter("from", from).setParameter("to", to).getResultList();
    }

    @Override
    public List<ChatMessage> findLatest(Joi joi, int limit) throws Exception {
        List<ChatMessage> latest = new ArrayList<>(entityManager.createQuery("SELECT m FROM ChatMessage m WHERE m.deleted = false AND m.joi = :joi ORDER BY m.sentAt DESC, m.id DESC", ChatMessage.class)
                .setParameter("joi", joi).setMaxResults(limit).getResultList());
        Collections.reverse(latest);
        return latest;
    }

    @Override
    public List<ChatMessage> findLatestExchanges(Joi joi, int limit) throws Exception {
        List<ChatMessage> latest = new ArrayList<>(entityManager.createQuery("SELECT m FROM ChatMessage m WHERE m.deleted = false AND m.joi = :joi AND m.type <> :log ORDER BY m.sentAt DESC, m.id DESC", ChatMessage.class)
                .setParameter("joi", joi).setParameter("log", ChatMessageType.LOG).setMaxResults(limit).getResultList());
        Collections.reverse(latest);
        return latest;
    }

    @Override
    public List<ChatMessage> findLatestExchangesSince(Joi joi, Date since, int limit) throws Exception {
        List<ChatMessage> latest = new ArrayList<>(entityManager.createQuery("SELECT m FROM ChatMessage m WHERE m.deleted = false AND m.joi = :joi AND m.type <> :log AND m.sentAt >= :since ORDER BY m.sentAt DESC, m.id DESC", ChatMessage.class)
                .setParameter("joi", joi).setParameter("log", ChatMessageType.LOG).setParameter("since", since).setMaxResults(limit).getResultList());
        Collections.reverse(latest);
        return latest;
    }

    @Override
    public List<ChatMessage> findBefore(Joi joi, ChatMessage first, int limit) throws Exception {
        List<ChatMessage> earlier = new ArrayList<>(entityManager.createQuery("SELECT m FROM ChatMessage m WHERE m.deleted = false AND m.joi = :joi "
                        + "AND (m.sentAt < :sentAt OR (m.sentAt = :sentAt AND m.id < :id)) ORDER BY m.sentAt DESC, m.id DESC", ChatMessage.class)
                .setParameter("joi", joi).setParameter("sentAt", first.getSentAt()).setParameter("id", first.getId())
                .setMaxResults(limit).getResultList());
        Collections.reverse(earlier);
        return earlier;
    }

    @Override
    public Map<Long, Date> findLastSentAt(Collection<Joi> jois) throws Exception {
        Map<Long, Date> last = new HashMap<>();
        List<Long> ids = jois.stream().map(Joi::getId).filter(java.util.Objects::nonNull).toList();
        for (List<Long> chunk : chunks(ids)) {
            for (Object[] row : entityManager.createQuery("SELECT m.joi.id, MAX(m.sentAt) FROM ChatMessage m WHERE m.deleted = false AND m.type <> :log "
                            + "AND m.joi.id IN :ids GROUP BY m.joi.id", Object[].class)
                    .setParameter("log", ChatMessageType.LOG).setParameter("ids", chunk).getResultList()) {
                last.put((Long) row[0], (Date) row[1]);
            }
        }
        return last;
    }

    @Override
    public boolean hasAny(Joi joi) throws Exception {
        return !entityManager.createQuery("SELECT m.id FROM ChatMessage m WHERE m.deleted = false AND m.type <> :log AND m.joi = :joi", Long.class)
                .setParameter("log", ChatMessageType.LOG).setParameter("joi", joi).setMaxResults(1).getResultList().isEmpty();
    }
}
