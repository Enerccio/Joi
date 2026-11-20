package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of {@code JoiPromptField.WEB_TASK_PROMPT}: what the stronger model is told when it does a search or reads a
 * page for her (a task, see {@code DESIGN.md} → Tasks after the reply).
 */
public class WebTaskTemplateData extends JoiTemplateData {

    /** Today's date, as the model needs it to judge what is recent ({@code 2026-11-20}). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_TODAY)
    private String today;

    /** What she wants to know; empty when only a page is to be read. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_QUESTION)
    private String question;

    /** The address of the page to read; empty for a search. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_URL)
    private String url;

    /** The names of the sources it may search, one per line with what each is; empty when it has no search. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_SOURCES)
    private String sources;

    /** How many searches it may make. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_MAX_SEARCHES)
    private String maxSearches;

    /** How many pages it may read. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_MAX_PAGES)
    private String maxPages;

    public String getToday() {
        return today;
    }

    public void setToday(String today) {
        this.today = today;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public String getMaxSearches() {
        return maxSearches;
    }

    public void setMaxSearches(String maxSearches) {
        this.maxSearches = maxSearches;
    }

    public String getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(String maxPages) {
        this.maxPages = maxPages;
    }
}
