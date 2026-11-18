package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import com.github.enerccio.marginalia.domain.traits.Fulltextable;
import jakarta.persistence.*;

import java.util.Date;

/**
 * A fact the assistant remembers (see {@code DESIGN.md} → Memory): a short, editable text with its source, recalled
 * by keywords (later embeddings) into the prompt. Its {@link MemoryImportance} decides what is recalled first and
 * what may be dropped.
 */
@Entity
@Table(name = "memories")
public class Memory extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryImportance importance = MemoryImportance.MEDIUM;

    /** Learned in privacy mode: recalled only into prompts for local models. */
    @Column(nullable = false)
    private boolean privateMode;

    /** The message it was learned from, null when the user wrote it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_message_id")
    @CleanupReference(Policy.WEAK)
    private ChatMessage sourceMessage;

    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String text;

    /** The words that recall it, comma separated. */
    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String keywords;

    /** Whether the user approved it; the assistant's own memories start unapproved. */
    @ExtendedAttribute
    @Transient
    private Boolean approved;

    /** When it was last put into a prompt, null when never. */
    @ExtendedAttribute
    @Transient
    private Date lastRecalled;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public MemoryImportance getImportance() {
        return importance;
    }

    public void setImportance(MemoryImportance importance) {
        this.importance = importance;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

    public ChatMessage getSourceMessage() {
        return sourceMessage;
    }

    public void setSourceMessage(ChatMessage sourceMessage) {
        this.sourceMessage = sourceMessage;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public Boolean getApproved() {
        return approved;
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public Date getLastRecalled() {
        return lastRecalled;
    }

    public void setLastRecalled(Date lastRecalled) {
        this.lastRecalled = lastRecalled;
    }
}
