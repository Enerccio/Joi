package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the summary of a week, a month or a year ({@code JoiPromptField.WEEK_SUMMARY_PROMPT},
 * {@code MONTH_SUMMARY_PROMPT}, {@code YEAR_SUMMARY_PROMPT}).
 */
public class PeriodSummaryTemplateData extends JoiTemplateData {

    /** The time it covers, as dates. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PERIOD)
    private String period;

    /** The summaries of that time, the oldest first. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PERIOD_SUMMARIES)
    private String summaries;

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getSummaries() {
        return summaries;
    }

    public void setSummaries(String summaries) {
        this.summaries = summaries;
    }

}
