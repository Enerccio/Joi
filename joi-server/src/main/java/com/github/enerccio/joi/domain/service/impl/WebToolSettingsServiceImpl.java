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
import com.github.enerccio.joi.domain.web.PageFetcher;
import com.github.enerccio.joi.domain.web.SearchResult;
import com.github.enerccio.joi.domain.web.WebException;
import com.github.enerccio.joi.domain.web.WebSearchClient;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
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
        return search(found.metadata(), found.from());
    }

    @Override
    @CommonTxReadOnly
    public Search searchIn(ToolScope scope, Joi joi) throws Exception {
        ToolMetadata metadata = metadataService.find(scope, joi, ServerTool.WEB_SEARCH);
        return search(metadata != null && metadata.hasConfig() ? metadata : null, scope);
    }

    private Search search(ToolMetadata metadata, ToolScope from) {
        if (metadata == null) {
            return new Search(WebSearchConfig.from(null), null, Map.of(), null);
        }
        WebSearchConfig config = WebSearchConfig.from(metadata.config());
        Map<String, String> keys = new LinkedHashMap<>();
        for (SearchSource source : config.sources()) {
            String key = metadataService.secret(metadata, WebSearchConfig.keyName(source));
            if (key != null) {
                keys.put(WebSearchConfig.keyName(source), key);
            }
        }
        return new Search(config, from, keys, metadataService.secret(metadata, WebSearchConfig.PROXY_SECRET));
    }

    @Override
    @CommonTxReadOnly
    public Pages pages(Joi joi) throws Exception {
        Config found = permissionService.config(joi, ServerTool.FETCH_PAGE);
        return confined(pages(found.metadata(), found.from()), found.from());
    }

    @Override
    @CommonTxReadOnly
    public boolean privateAllowed() throws Exception {
        ToolMetadata admin = metadataService.find(ToolScope.APP, null, ServerTool.FETCH_PAGE);
        return admin != null && admin.hasConfig() && PageConfig.from(admin.config()).allowPrivate();
    }

    /** The options of a place below the administrator's without the private addresses the administrator does not allow. */
    private Pages confined(Pages pages, ToolScope from) throws Exception {
        if (from == ToolScope.APP || !pages.config().allowPrivate() || privateAllowed()) {
            return pages;
        }
        return new Pages(pages.config().withoutPrivate(), pages.from(), pages.token(), pages.proxyAuth());
    }

    private PageConfig confined(PageConfig config, ToolScope scope) throws Exception {
        return scope == ToolScope.APP || !config.allowPrivate() || privateAllowed() ? config : config.withoutPrivate();
    }

    @Override
    @CommonTxReadOnly
    public Pages pagesIn(ToolScope scope, Joi joi) throws Exception {
        ToolMetadata metadata = metadataService.find(scope, joi, ServerTool.FETCH_PAGE);
        return confined(pages(metadata != null && metadata.hasConfig() ? metadata : null, scope), scope);
    }

    private Pages pages(ToolMetadata metadata, ToolScope from) {
        if (metadata == null) {
            return new Pages(PageConfig.from(null), null, null, null);
        }
        return new Pages(PageConfig.from(metadata.config()), from, metadataService.secret(metadata, PageConfig.TOKEN_SECRET),
                metadataService.secret(metadata, WebSearchConfig.PROXY_SECRET));
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
        save(scope, joi, ServerTool.FETCH_PAGE, confined(config, scope).toJson(), Set.of(PageConfig.TOKEN_SECRET, WebSearchConfig.PROXY_SECRET), secrets);
    }

    @Override
    @CommonTx
    public void clearSearch(ToolScope scope, Joi joi) throws Exception {
        clear(scope, joi, ServerTool.WEB_SEARCH);
    }

    @Override
    @CommonTx
    public void clearPages(ToolScope scope, Joi joi) throws Exception {
        clear(scope, joi, ServerTool.FETCH_PAGE);
    }

    private void clear(ToolScope scope, Joi joi, ServerTool tool) throws Exception {
        ToolMetadata metadata = metadataService.find(scope, joi, tool);
        if (metadata != null) {
            metadata.getAttributes().remove(ToolMetadata.CONFIG);
            metadata.getAttributes().remove(ToolMetadata.SECRETS);
            metadataService.save(metadata);
        }
    }

    @Override
    public SearchResult testSearch(ToolScope scope, Joi joi, WebSearchConfig config, SearchSource source, String typedKey,
                                   String typedProxyAuth, String query) throws Exception, WebException {
        Search saved = searchIn(scope, joi);
        String key = StringUtils.defaultIfBlank(typedKey, saved.keys().get(WebSearchConfig.keyName(source)));
        return new WebSearchClient(config).search(source, key, StringUtils.defaultIfBlank(typedProxyAuth, saved.proxyAuth()), query);
    }

    @Override
    public String testPage(ToolScope scope, Joi joi, PageConfig config, String typedToken, String typedProxyAuth, String url)
            throws Exception, WebException {
        Pages saved = pagesIn(scope, joi);
        Search search = searchIn(scope, joi);
        return new PageFetcher(confined(config, scope), StringUtils.defaultIfBlank(typedToken, saved.token()),
                StringUtils.defaultIfBlank(typedProxyAuth, saved.proxyAuth()), search.config().userAgent()).fetch(url);
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
