package com.github.enerccio.joi.domain.web;

/**
 * One result of a search.
 *
 * @param title   what the page calls itself
 * @param url     where it is
 * @param snippet the part of it that matched, short; may be empty
 * @param source  the name of the source that found it
 */
public record SearchHit(String title, String url, String snippet, String source) {
}
