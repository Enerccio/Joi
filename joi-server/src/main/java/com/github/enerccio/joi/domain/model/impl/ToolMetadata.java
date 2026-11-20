package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.persistence.*;

/**
 * The settings of one server tool in one place of the chain (see {@code DESIGN.md} → Tool permissions): of one
 * assistant, of one user, or of the administrator. The three columns find it; everything else is the dynamic JSON of
 * its attributes, so a new option needs no migration: {@code permission} (absent: inherit), {@code config} (the tool's
 * options) and {@code secrets} (keys and tokens by name, each protected with {@code InferenceServices.protectSecret}:
 * {@code ToolMetadataService} does it, nothing else touches them).
 */
@Entity
@Table(name = "tool_metadata")
public class ToolMetadata extends ExtendableEntity {

    public static final String PERMISSION = "permission";
    public static final String CONFIG = "config";
    public static final String SECRETS = "secrets";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ToolScope scope;

    /** The assistant, for {@link ToolScope#JOI}; null otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id")
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    /** The function name of the tool ({@code ServerTool.function()}). */
    @Column(name = "tool_key", nullable = false, length = 64)
    private String toolKey;

    public ToolScope getScope() {
        return scope;
    }

    public void setScope(ToolScope scope) {
        this.scope = scope;
    }

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public String getToolKey() {
        return toolKey;
    }

    public void setToolKey(String toolKey) {
        this.toolKey = toolKey;
    }

    /** What it says about the tool, null for nothing (inherit). */
    public ToolPermission getPermission() {
        JsonElement value = attributes().get(PERMISSION);
        return value != null && value.isJsonPrimitive() ? ToolPermission.parse(value.getAsString()) : null;
    }

    public void setPermission(ToolPermission permission) {
        if (permission == null) {
            attributes().remove(PERMISSION);
        } else {
            attributes().addProperty(PERMISSION, permission.name().toLowerCase());
        }
    }

    /** The tool's options; created (empty) when there are none. */
    public JsonObject config() {
        return object(CONFIG);
    }

    /** Whether it has options of its own: a link without any is passed over when the options are looked up. */
    public boolean hasConfig() {
        JsonElement value = attributes().get(CONFIG);
        return value != null && value.isJsonObject() && !value.getAsJsonObject().isEmpty();
    }

    /** The secrets as they are kept: protected. Read them through {@code ToolMetadataService}. */
    public JsonObject protectedSecrets() {
        return object(SECRETS);
    }

    private JsonObject object(String name) {
        JsonObject attributes = attributes();
        JsonElement value = attributes.get(name);
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        attributes.add(name, created);
        return created;
    }

    private JsonObject attributes() {
        if (getAttributes() == null) {
            setAttributes(new JsonObject());
        }
        return getAttributes();
    }
}
