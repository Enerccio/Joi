package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.MessagePrompt;
import com.github.enerccio.joi.domain.repository.MessagePromptRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.List;

public class JpaMessagePromptRepository extends JpaExtendableRepository<MessagePrompt> implements MessagePromptRepository {

    @Override
    protected Class<MessagePrompt> getEntityClass() {
        return MessagePrompt.class;
    }

    @Override
    public MessagePrompt findByMessage(ChatMessage message) throws Exception {
        if (message == null || message.getId() == null) {
            return null;
        }
        List<MessagePrompt> found = entityManager.createQuery("SELECT p FROM MessagePrompt p WHERE p.deleted = false AND p.message = :message ORDER BY p.id DESC", MessagePrompt.class)
                .setParameter("message", message).setMaxResults(1).getResultList();
        return found.isEmpty() ? null : found.getFirst();
    }
}
