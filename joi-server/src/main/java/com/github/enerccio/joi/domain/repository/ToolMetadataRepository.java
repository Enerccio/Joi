package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;
import com.github.enerccio.marginalia.domain.security.model.User;

import java.util.List;

/** The settings of the server's tools in the places of the chain. */
public interface ToolMetadataRepository extends ExtendableRepository<ToolMetadata> {

    /**
     * The settings of a tool in one place: of the assistant ({@code joi}), of the user ({@code user}), or the
     * administrator's (neither is read), null when there are none.
     */
    ToolMetadata find(ToolScope scope, Joi joi, User user, String toolKey) throws Exception;

    /** All the settings in one place, whatever the tool. */
    List<ToolMetadata> findAll(ToolScope scope, Joi joi, User user) throws Exception;
}
