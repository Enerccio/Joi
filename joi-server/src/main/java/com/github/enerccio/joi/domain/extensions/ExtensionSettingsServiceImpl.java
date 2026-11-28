package com.github.enerccio.joi.domain.extensions;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.extensions.ExtensionConfig;
import com.github.enerccio.joi.extensions.ExtensionSetting;
import com.github.enerccio.joi.extensions.ExtensionSettings;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.google.gson.JsonElement;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ExtensionSettingsServiceImpl implements ExtensionSettingsService {

    @Autowired
    private ToolMetadataService metadataService;

    @Autowired
    private JoiExtensions extensions;

    @Override
    public ExtensionSettings declared(String owner) {
        return extensions.settings(owner);
    }

    @Override
    @CommonTxReadOnly
    public ExtensionConfig resolve(Joi joi, String owner) throws Exception {
        ExtensionSettings settings = declared(owner);
        if (settings == null) {
            return ExtensionConfig.empty();
        }
        String key = ExtensionSettingsService.key(owner);
        ToolMetadata[] chain = {
                joi == null ? null : metadataService.find(ToolScope.JOI, joi, key),
                metadataService.find(ToolScope.USER, null, key),
                metadataService.find(ToolScope.APP, null, key)};
        ToolScope[] places = {ToolScope.JOI, ToolScope.USER, ToolScope.APP};
        Map<String, String> values = new HashMap<>();
        Map<String, ToolScope> from = new HashMap<>();
        for (ExtensionSetting field : settings.fields()) {
            for (int i = 0; i < chain.length; i++) {
                String value = chain[i] == null ? null : value(chain[i], field);
                if (value != null) {
                    values.put(field.key(), value);
                    from.put(field.key(), places[i]);
                    break;
                }
            }
        }
        return new ExtensionConfig(values, from);
    }

    @Override
    @CommonTxReadOnly
    public Place place(ToolScope scope, Joi joi, String owner) throws Exception {
        ExtensionSettings settings = declared(owner);
        ToolMetadata metadata = metadataService.find(scope, scope == ToolScope.JOI ? joi : null, ExtensionSettingsService.key(owner));
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> secrets = new LinkedHashSet<>();
        if (settings != null && metadata != null) {
            for (ExtensionSetting field : settings.fields()) {
                if (field.secret()) {
                    if (metadataService.hasSecret(metadata, field.key())) {
                        secrets.add(field.key());
                    }
                } else {
                    String value = value(metadata, field);
                    if (value != null) {
                        values.put(field.key(), value);
                    }
                }
            }
        }
        return new Place(values, secrets);
    }

    @Override
    @CommonTx
    public void save(ToolScope scope, Joi joi, String owner, Map<String, String> values, Map<String, String> secrets) throws Exception {
        ExtensionSettings settings = declared(owner);
        if (settings == null) {
            throw new IllegalStateException("The extension " + owner + " has no settings (is it loaded?)");
        }
        Map<String, String> texts = values == null ? Map.of() : values;
        Map<String, String> hidden = secrets == null ? Map.of() : secrets;
        for (String key : texts.keySet()) {
            ExtensionSetting field = settings.field(key);
            if (field == null || field.secret()) {
                throw new IllegalArgumentException("The extension " + owner + " has no text setting " + key);
            }
        }
        for (String key : hidden.keySet()) {
            ExtensionSetting field = settings.field(key);
            if (field == null || !field.secret()) {
                throw new IllegalArgumentException("The extension " + owner + " has no secret " + key);
            }
        }
        ToolMetadata metadata = metadataService.edit(scope, scope == ToolScope.JOI ? joi : null, ExtensionSettingsService.key(owner));
        texts.forEach((key, value) -> {
            if (StringUtils.isBlank(value)) {
                metadata.config().remove(key);
            } else {
                metadata.config().addProperty(key, value.strip());
            }
        });
        hidden.forEach((key, value) -> {
            if (value != null) {
                metadataService.setSecret(metadata, key, value);
            }
        });
        if (metadata.getId() == null && !metadata.hasConfig() && metadata.protectedSecrets().isEmpty()) {
            return;
        }
        metadataService.save(metadata);
    }

    @Override
    @CommonTx
    public void clear(ToolScope scope, Joi joi, String owner) throws Exception {
        ToolMetadata metadata = metadataService.find(scope, scope == ToolScope.JOI ? joi : null, ExtensionSettingsService.key(owner));
        if (metadata != null) {
            // the row stays: it may hold the permission of something else under the same key one day, and a delete would be soft anyway
            metadata.getAttributes().remove(ToolMetadata.CONFIG);
            metadata.getAttributes().remove(ToolMetadata.SECRETS);
            metadataService.save(metadata);
        }
    }

    /** The value of the field in the place, null when it is not set there (a secret that cannot be revealed is not set either). */
    private String value(ToolMetadata metadata, ExtensionSetting field) {
        if (field.secret()) {
            return metadataService.secret(metadata, field.key());
        }
        if (!metadata.hasConfig()) {
            return null;
        }
        JsonElement value = metadata.config().get(field.key());
        return value != null && value.isJsonPrimitive() && StringUtils.isNotBlank(value.getAsString()) ? value.getAsString() : null;
    }
}
