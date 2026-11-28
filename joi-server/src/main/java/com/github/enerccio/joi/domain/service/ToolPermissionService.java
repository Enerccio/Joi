package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;

/**
 * What a server tool is for an assistant: the chain of the assistant, the user and the administrator (see
 * {@code DESIGN.md} → Tool permissions). The value is the first one set along the chain, else the built-in one of the
 * tool; the administrator's is also a ceiling, so what is set below it can only be as strict or stricter. The tool's
 * options follow the chain without the ceiling: the first place that has any.
 */
public interface ToolPermissionService {

    /**
     * How the permission of a tool comes about.
     *
     * @param assistant what the assistant says, null for nothing (or no assistant)
     * @param user      what the user says, null for nothing
     * @param admin     what the administrator says, null for nothing
     * @param value     the first of the three that is set, else the tool's own
     * @param effective what is in force: the value, or the administrator's when that is stricter
     * @param source    where {@code effective} comes from, null for the tool's own
     */
    record Chain(ToolPermission assistant, ToolPermission user, ToolPermission admin, ToolPermission value,
                 ToolPermission effective, ToolScope source) {

        /** Whether the administrator's ceiling made it stricter than what was set below it. */
        public boolean capped() {
            return effective != value;
        }
    }

    /** The options of a tool, and where they came from: {@code metadata} is null (and {@code from} too) for none. */
    record Config(ToolScope from, ToolMetadata metadata) {
    }

    /** The chain of the tool for the assistant; with no assistant (null) its link is left out: the user's own settings page. */
    Chain chain(Joi joi, ApprovableTool tool) throws Exception;

    /** What the tool is for the assistant now. */
    default ToolPermission effective(Joi joi, ApprovableTool tool) throws Exception {
        return chain(joi, tool).effective();
    }

    /** The options of the tool: the assistant's, else the user's, else the administrator's. */
    Config config(Joi joi, ApprovableTool tool) throws Exception;
}
