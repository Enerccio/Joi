package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of what she is told when a summary was made ({@code JoiPromptField.SUMMARY_EVENT}).
 */
public class SummaryEventTemplateData extends JoiTemplateData {

    /** What was summarized, in words: the conversation so far, a day, a week. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SUMMARY_KIND)
    private String kind;

    /** The time it covers, as dates. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SPAN)
    private String span;

    /** The summary, as it reads (not {@code summary}: that is a SillyTavern macro the platform ignores). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SUMMARY_TEXT)
    private String digest;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSpan() {
        return span;
    }

    public void setSpan(String span) {
        this.span = span;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

}
