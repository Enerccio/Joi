package com.github.enerccio.joi.domain.web;

import java.util.List;

/**
 * What a search found.
 *
 * @param hits   the results, best first
 * @param answer an answer the source wrote itself (an AI search does), null for none
 */
public record SearchResult(List<SearchHit> hits, String answer) {

    public SearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
