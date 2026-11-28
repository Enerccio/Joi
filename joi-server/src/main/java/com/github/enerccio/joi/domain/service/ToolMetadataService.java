package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.repository.ToolMetadataRepository;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/**
 * The settings of the server's tools in the places of the chain ({@link ToolMetadata}). Reading and writing are checked
 * by the place: the assistant's are the owner's, the user's are that user's own, and the administrator's are written
 * by administrators only (everybody reads them: they are part of every chain). The secrets are protected here.
 */
public interface ToolMetadataService extends ExtendableService<ToolMetadata, ToolMetadataRepository> {

    /** The settings of the tool in the place ({@code joi} only for {@link ToolScope#JOI}), null when there are none. */
    ToolMetadata find(ToolScope scope, Joi joi, ApprovableTool tool) throws Exception;

    /** What {@link #find} gives, else a new one for the place, not saved. */
    ToolMetadata edit(ToolScope scope, Joi joi, ApprovableTool tool) throws Exception;

    /**
     * The settings kept under a key in the place, null when there are none: a tool's function name, or the key of the settings of an
     * extension ({@code ExtensionSettingsService}).
     */
    ToolMetadata find(ToolScope scope, Joi joi, String toolKey) throws Exception;

    /** What {@link #find(ToolScope, Joi, String)} gives, else a new one for the place, not saved. */
    ToolMetadata edit(ToolScope scope, Joi joi, String toolKey) throws Exception;

    /** All the settings in the place. */
    List<ToolMetadata> findAll(ToolScope scope, Joi joi) throws Exception;

    /** Whether the secret is set. */
    boolean hasSecret(ToolMetadata metadata, String name);

    /** The secret in the clear, null when it is not set or was protected with another installation's key. */
    String secret(ToolMetadata metadata, String name);

    /** Sets the secret, protected; a blank one removes it. Save the metadata after. */
    void setSecret(ToolMetadata metadata, String name, String secret);
}
