package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.repository.ToolMetadataRepository;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.security.AdminGuard;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.google.gson.JsonElement;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class ToolMetadataServiceImpl extends ExtendableServiceImpl<ToolMetadata, ToolMetadataRepository> implements ToolMetadataService {

    @Autowired
    private AdminGuard adminGuard;

    @Autowired
    private InferenceServices inferenceServices;

    @Autowired
    private JoiService joiService;

    @Override
    @CommonTxReadOnly
    public ToolMetadata find(ToolScope scope, Joi joi, ServerTool tool) throws Exception {
        User user = reading(scope, joi);
        return getRepository().find(scope, joi, user, tool.function());
    }

    @Override
    @CommonTxReadOnly
    public ToolMetadata edit(ToolScope scope, Joi joi, ServerTool tool) throws Exception {
        ToolMetadata found = find(scope, joi, tool);
        if (found != null) {
            return found;
        }
        ToolMetadata created = new ToolMetadata();
        created.setScope(scope);
        created.setJoi(scope == ToolScope.JOI ? joi : null);
        created.setToolKey(tool.function());
        return created;
    }

    @Override
    @CommonTxReadOnly
    public List<ToolMetadata> findAll(ToolScope scope, Joi joi) throws Exception {
        return getRepository().findAll(scope, joi, reading(scope, joi));
    }

    @Override
    @CommonTx
    public ToolMetadata save(ToolMetadata metadata) throws Exception {
        writing(metadata);
        if (metadata.getId() == null && getRepository().find(metadata.getScope(), metadata.getJoi(), currentUserOrNull(metadata.getScope()),
                metadata.getToolKey()) != null) {
            throw new IllegalStateException("The tool " + metadata.getToolKey() + " has settings in this place already");
        }
        return super.save(metadata);
    }

    @Override
    @CommonTx
    public ToolMetadata delete(ToolMetadata metadata, boolean hard) throws Exception {
        writing(metadata);
        return super.delete(metadata, hard);
    }

    @Override
    public boolean hasSecret(ToolMetadata metadata, String name) {
        JsonElement value = metadata.protectedSecrets().get(name);
        return value != null && value.isJsonPrimitive() && StringUtils.isNotEmpty(value.getAsString());
    }

    @Override
    public String secret(ToolMetadata metadata, String name) {
        JsonElement value = metadata.protectedSecrets().get(name);
        return value != null && value.isJsonPrimitive() && StringUtils.isNotEmpty(value.getAsString())
                ? inferenceServices.revealSecret(value.getAsString()) : null;
    }

    @Override
    public void setSecret(ToolMetadata metadata, String name, String secret) {
        if (StringUtils.isBlank(secret)) {
            metadata.protectedSecrets().remove(name);
        } else {
            metadata.protectedSecrets().addProperty(name, inferenceServices.protectSecret(secret.strip()));
        }
    }

    /** Checks the place may be read by the caller; returns the user whose it is (null for the others). */
    private User reading(ToolScope scope, Joi joi) throws Exception {
        if (scope == ToolScope.JOI) {
            requireOwn(joi);
        }
        return currentUserOrNull(scope);
    }

    /** The managed user for the user's place (the session's own is not an entity a query takes), else null. */
    private User currentUserOrNull(ToolScope scope) throws Exception {
        return scope == ToolScope.USER ? userService.find(currentUser.getId()) : null;
    }

    private void writing(ToolMetadata metadata) throws Exception {
        if (metadata.getScope() == null || StringUtils.isBlank(metadata.getToolKey())) {
            throw new IllegalArgumentException("The settings need a place and a tool");
        }
        switch (metadata.getScope()) {
            case APP -> adminGuard.requireAdmin();
            case JOI -> requireOwn(metadata.getJoi());
            case USER -> {
                if (metadata.getOwner() != null && !metadata.getOwner().getId().equals(currentUser.getId())) {
                    throw new SecurityException("Not the settings of the user");
                }
            }
        }
    }

    /** The assistant is read again: the one passed may be a proxy of a session that is over. */
    private void requireOwn(Joi joi) throws Exception {
        Joi own = joi == null || joi.getId() == null ? null : joiService.find(joi.getId());
        if (own == null || own.isDeleted() || own.getOwner() == null || !own.getOwner().getId().equals(currentUser.getId())) {
            throw new SecurityException("Not an assistant of the user");
        }
    }
}
