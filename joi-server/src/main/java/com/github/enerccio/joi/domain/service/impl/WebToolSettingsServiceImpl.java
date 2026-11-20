package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.service.ToolPermissionService.Config;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.PageConfig;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.WebSearchConfig;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WebToolSettingsServiceImpl implements WebToolSettingsService {

    @Autowired
    private ToolPermissionService permissionService;

    @Autowired
    private ToolMetadataService metadataService;

    @Override
    @CommonTxReadOnly
    public Search search(Joi joi) throws Exception {
        Config found = permissionService.config(joi, ServerTool.WEB_SEARCH);
        if (found.metadata() == null) {
            return new Search(WebSearchConfig.from(null), null, Map.of(), null);
        }
        WebSearchConfig config = WebSearchConfig.from(found.metadata().config());
        Map<String, String> keys = new LinkedHashMap<>();
        for (SearchSource source : config.sources()) {
            String key = metadataService.secret(found.metadata(), WebSearchConfig.keyName(source));
            if (key != null) {
                keys.put(WebSearchConfig.keyName(source), key);
            }
        }
        return new Search(config, found.from(), keys, metadataService.secret(found.metadata(), WebSearchConfig.PROXY_SECRET));
    }

    @Override
    @CommonTxReadOnly
    public Pages pages(Joi joi) throws Exception {
        Config found = permissionService.config(joi, ServerTool.FETCH_PAGE);
        if (found.metadata() == null) {
            return new Pages(PageConfig.from(null), null, null, null);
        }
        return new Pages(PageConfig.from(found.metadata().config()), found.from(),
                metadataService.secret(found.metadata(), PageConfig.TOKEN_SECRET),
                metadataService.secret(found.metadata(), WebSearchConfig.PROXY_SECRET));
    }

    @Override
    @CommonTx
    public void saveSearch(ToolScope scope, Joi joi, WebSearchConfig config, Map<String, String> secrets) throws Exception {
        Set<String> known = new HashSet<>();
        known.add(WebSearchConfig.PROXY_SECRET);
        config.sources().forEach(source -> known.add(WebSearchConfig.keyName(source)));
        save(scope, joi, ServerTool.WEB_SEARCH, config.toJson(), known, secrets);
    }

    @Override
    @CommonTx
    public void savePages(ToolScope scope, Joi joi, PageConfig config, Map<String, String> secrets) throws Exception {
        save(scope, joi, ServerTool.FETCH_PAGE, config.toJson(), Set.of(PageConfig.TOKEN_SECRET, WebSearchConfig.PROXY_SECRET), secrets);
    }

    private void save(ToolScope scope, Joi joi, ServerTool tool, JsonObject config, Set<String> known, Map<String, String> secrets) throws Exception {
        ToolMetadata metadata = metadataService.edit(scope, joi, tool);
        metadata.getAttributes().add(ToolMetadata.CONFIG, config);
        if (secrets != null) {
            secrets.forEach((name, value) -> {
                if (known.contains(name) && value != null) {
                    metadataService.setSecret(metadata, name, value);
                }
            });
        }
        // a secret nothing uses any more is not kept
        for (String name : List.copyOf(metadata.protectedSecrets().keySet())) {
            if (!known.contains(name)) {
                metadata.protectedSecrets().remove(name);
            }
        }
        metadataService.save(metadata);
    }
}
