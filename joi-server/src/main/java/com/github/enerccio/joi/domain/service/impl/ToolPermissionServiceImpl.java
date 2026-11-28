package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.springframework.beans.factory.annotation.Autowired;

public class ToolPermissionServiceImpl implements ToolPermissionService {

    @Autowired
    private ToolMetadataService toolMetadataService;

    @Override
    @CommonTxReadOnly
    public Chain chain(Joi joi, ApprovableTool tool) throws Exception {
        ToolPermission assistant = joi == null ? null : permission(toolMetadataService.find(ToolScope.JOI, joi, tool));
        ToolPermission user = permission(toolMetadataService.find(ToolScope.USER, null, tool));
        ToolPermission admin = permission(toolMetadataService.find(ToolScope.APP, null, tool));
        ToolPermission value;
        ToolScope source;
        if (assistant != null) {
            value = assistant;
            source = ToolScope.JOI;
        } else if (user != null) {
            value = user;
            source = ToolScope.USER;
        } else if (admin != null) {
            value = admin;
            source = ToolScope.APP;
        } else {
            value = tool.fallback();
            source = null;
        }
        ToolPermission effective = ToolPermission.stricter(value, admin);
        if (effective != value) {
            source = ToolScope.APP;
        }
        return new Chain(assistant, user, admin, value, effective, source);
    }

    @Override
    @CommonTxReadOnly
    public Config config(Joi joi, ApprovableTool tool) throws Exception {
        if (joi != null) {
            ToolMetadata own = toolMetadataService.find(ToolScope.JOI, joi, tool);
            if (own != null && own.hasConfig()) {
                return new Config(ToolScope.JOI, own);
            }
        }
        ToolMetadata user = toolMetadataService.find(ToolScope.USER, null, tool);
        if (user != null && user.hasConfig()) {
            return new Config(ToolScope.USER, user);
        }
        ToolMetadata admin = toolMetadataService.find(ToolScope.APP, null, tool);
        return admin != null && admin.hasConfig() ? new Config(ToolScope.APP, admin) : new Config(null, null);
    }

    private static ToolPermission permission(ToolMetadata metadata) {
        return metadata == null ? null : metadata.getPermission();
    }
}
