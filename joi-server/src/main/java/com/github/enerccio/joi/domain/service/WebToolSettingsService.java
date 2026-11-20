package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.PageConfig;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.WebSearchConfig;

import java.util.Map;

/**
 * The options and secrets of {@code web_search} and {@code fetch_page} along the chain of the assistant, the user and
 * the administrator ({@link ToolPermissionService#config}): the first place that has options of its own gives them, with
 * its secrets. Writing is checked by the place ({@link ToolMetadataService}).
 */
public interface WebToolSettingsService {

    /**
     * The search options in force for the assistant.
     *
     * @param from      where they come from, null when nobody set any (the defaults)
     * @param keys      the API keys by the name of the secret ({@link WebSearchConfig#keyName}), in the clear
     * @param proxyAuth {@code user:password} of the proxy, null for none
     */
    record Search(WebSearchConfig config, ToolScope from, Map<String, String> keys, String proxyAuth) {

        /** The key of the source, null for none. */
        public String key(SearchSource source) {
            return keys.get(WebSearchConfig.keyName(source));
        }
    }

    /**
     * The page options in force for the assistant.
     *
     * @param token     the bearer token of the page reader, null for none
     * @param proxyAuth {@code user:password} of the proxy of direct reads, null for none
     */
    record Pages(PageConfig config, ToolScope from, String token, String proxyAuth) {
    }

    Search search(Joi joi) throws Exception;

    Pages pages(Joi joi) throws Exception;

    /**
     * Keeps the options in a place ({@code joi} only for {@link ToolScope#JOI}). {@code secrets} are changes: a name
     * with no entry stays as it is, a blank value removes it, any other sets it. The keys of sources that are no longer
     * in the options are removed.
     */
    void saveSearch(ToolScope scope, Joi joi, WebSearchConfig config, Map<String, String> secrets) throws Exception;

    /** As {@link #saveSearch}, for the page options. */
    void savePages(ToolScope scope, Joi joi, PageConfig config, Map<String, String> secrets) throws Exception;
}
