package com.github.enerccio.joi.domain.web;

import java.util.List;

/**
 * One place to search, as the user set it up. The API key is not here: it is a secret of the tool's settings, under
 * {@code source:<id>} ({@link WebSearchConfig#keyName}).
 *
 * @param id       what the secret and the choice of source are named by; stable
 * @param name     what the user and she call it
 * @param type     the kind of search
 * @param enabled  off sources are never used
 * @param local    whether it runs on the user's own network: the only ones used when the options say local only
 * @param url      the address: required for SearXNG, replaces the provider's own for the others (a mirror, a proxy)
 * @param engineId Google's {@code cx}
 * @param sites    limits the search to these sites (domains); empty for the whole web
 * @param proxy    a proxy of its own for this source, blank for the common one
 */
public record SearchSource(String id, String name, SearchSourceType type, boolean enabled, boolean local, String url,
                           String engineId, List<String> sites, String proxy) {

    public SearchSource {
        sites = sites == null ? List.of() : List.copyOf(sites);
    }
}
