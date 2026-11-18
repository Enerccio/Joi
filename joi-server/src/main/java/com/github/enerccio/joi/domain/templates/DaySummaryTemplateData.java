package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the summary of a day ({@code JoiPromptField.DAY_SUMMARY_PROMPT}).
 */
public class DaySummaryTemplateData extends JoiTemplateData {

    /** The day, as a date. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_DAY)
    private String day;

    /** The summaries of the day's conversations. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_SUMMARIES)
    private String summaries;

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public String getSummaries() {
        return summaries;
    }

    public void setSummaries(String summaries) {
        this.summaries = summaries;
    }

}
