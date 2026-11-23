package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.repository.ChatMessageRepository;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ChatMessageServiceImpl extends ExtendableServiceImpl<ChatMessage, ChatMessageRepository> implements ChatMessageService {

    @Autowired
    private Configuration configuration;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private com.github.enerccio.joi.domain.service.ChatImageService chatImageService;

    @Override
    public File newClipFile() throws Exception {
        return resourceService.newUploadFile();
    }

    @Override
    @CommonTx
    public ChatMessage attachClip(ChatMessage message, File clip, String extension, String mime) throws Exception {
        ChatMessage own = find(message.getId());
        Resource resource = resourceService.saveFile(clip, "voice." + extension, mime, ChatMessage.class, own.getId());
        own.setClipResourceId(resource.getId());
        return save(own);
    }

    @Override
    @CommonTxReadOnly
    public File clipFile(ChatMessage message) throws Exception {
        Resource resource = clip(message);
        return resource == null ? null : resourceService.getResourceFile(resource);
    }

    @Override
    @CommonTxReadOnly
    public String clipMime(ChatMessage message) throws Exception {
        Resource resource = clip(message);
        return resource == null ? null : resource.getMimeType();
    }

    private Resource clip(ChatMessage message) throws Exception {
        if (message == null || message.getClipResourceId() == null) {
            return null;
        }
        Resource resource = resourceService.find(message.getClipResourceId());
        return resource == null || resource.isDeleted() || resource.getOwner() == null || !resource.getOwner().getId().equals(currentUser.getId())
                ? null : resource;
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findBetween(Joi joi, Date from, Date to) throws Exception {
        return getRepository().findBetween(joi, from, to);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findLatest(Joi joi, int limit) throws Exception {
        return getRepository().findLatest(joi, limit);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findLatestExchanges(Joi joi, int limit) throws Exception {
        return getRepository().findLatestExchanges(joi, limit);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findLatestExchangesSince(Joi joi, Date since, int limit) throws Exception {
        return getRepository().findLatestExchangesSince(joi, since, limit);
    }

    @Override
    @CommonTxReadOnly
    public List<ChatMessage> findBefore(Joi joi, ChatMessage first, int limit) throws Exception {
        return getRepository().findBefore(joi, first, limit);
    }

    @Override
    @CommonTxReadOnly
    public Map<Long, Date> findLastSentAt(Collection<Joi> jois) throws Exception {
        return getRepository().findLastSentAt(jois);
    }

    @Override
    @CommonTxReadOnly
    public boolean hasAny(Joi joi) throws Exception {
        return getRepository().hasAny(joi);
    }

    @Override
    @CommonTx
    public int deleteMessages(Joi joi, Collection<Long> ids) throws Exception {
        int deleted = 0;
        java.util.List<ChatMessage> gone = new java.util.ArrayList<>();
        for (Long id : ids) {
            ChatMessage message = id == null ? null : find(id);
            if (message == null || message.isDeleted() || message.getJoi() == null
                    || !message.getJoi().getId().equals(joi.getId())) {
                continue;
            }
            delete(message, false);
            gone.add(message);
            deleted++;
        }
        // the images of a message go with it
        chatImageService.deleteOf(gone);
        return deleted;
    }
}
