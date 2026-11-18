package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import com.github.enerccio.marginalia.domain.traits.Fulltextable;
import jakarta.persistence.*;

import java.util.Date;

/**
 * A summary of a time of the conversation: {@link #getCoverFrom()} (inclusive) to {@link #getCoverTo()} (exclusive).
 * It covers a time, not a list of messages: a day summary covers the messages sent in its day, a week summary is made
 * from the day summaries of its week (through the week's prompt), and so on (see {@link SummaryType}). A prompt that
 * shows the past takes the longest summary of a time and skips the shorter ones it covers ({@code SummaryCoverage}).
 */
@Entity
@Table(name = "summaries")
public class Summary extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SummaryType summaryType = SummaryType.DAY;

    @Column(nullable = false)
    private Date coverFrom;

    @Column(nullable = false)
    private Date coverTo;

    /** Made of private messages: only for local models, left out of roll-ups for external ones. */
    @Column(nullable = false)
    private boolean privateMode;

    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String summary;

    @ExtendedAttribute
    @Transient
    private Long summaryTokens;

    @ExtendedAttribute
    @Transient
    private String reasoning;

    @ExtendedAttribute
    @Transient
    private Long reasoningTokens;

    @ExtendedAttribute
    @Transient
    private String modelUsed;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public SummaryType getSummaryType() {
        return summaryType;
    }

    public void setSummaryType(SummaryType summaryType) {
        this.summaryType = summaryType;
    }

    public Date getCoverFrom() {
        return coverFrom;
    }

    public void setCoverFrom(Date coverFrom) {
        this.coverFrom = coverFrom;
    }

    public Date getCoverTo() {
        return coverTo;
    }

    public void setCoverTo(Date coverTo) {
        this.coverTo = coverTo;
    }

    /** Whether this summary's time holds the whole time of the other one. */
    public boolean covers(Summary other) {
        return !coverFrom.after(other.coverFrom) && !coverTo.before(other.coverTo);
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getSummaryTokens() {
        return summaryTokens;
    }

    public void setSummaryTokens(Long summaryTokens) {
        this.summaryTokens = summaryTokens;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Long getReasoningTokens() {
        return reasoningTokens;
    }

    public void setReasoningTokens(Long reasoningTokens) {
        this.reasoningTokens = reasoningTokens;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }
}
