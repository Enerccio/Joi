package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the event of the user asking to change parts of her body when her personality lets her decide
 * ({@code JoiPromptField.PART_REQUEST_EVENT}): what she is told, as the user's turn, and what she answers with the tool
 * {@code decide_part_change}.
 */
public class PartRequestTemplateData extends JoiTemplateData {

    /** What is asked, one request on a line, each with its number (the one the tool takes). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_REQUESTS)
    private String requests;

    /** What else happened to her meanwhile, one event on a line; empty when nothing. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OTHER_EVENTS)
    private String events;

    /** How she decides: what her personality says about it ({@code {{user}}} and {{char}} work in it). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CONSENT)
    private String consent;

    public String getRequests() {
        return requests;
    }

    public void setRequests(String requests) {
        this.requests = requests;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getConsent() {
        return consent;
    }

    public void setConsent(String consent) {
        this.consent = consent;
    }
}
