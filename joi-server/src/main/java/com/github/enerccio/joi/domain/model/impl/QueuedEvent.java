package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.*;

import java.util.Date;

/**
 * An event that happened while the assistant could not take it: no node was connected (the user added a model in the
 * UI while every node was shut down). It waits here, in the order it happened, and when a node connects the waiting
 * events go to her as {@link com.github.enerccio.joi.domain.collections.ChatMessageType#EVENT EVENT} messages
 * ({@link #getDeliveredIn()}), so she can still react to them ("oh, a new model?").
 */
@Entity
@Table(name = "queued_events")
public class QueuedEvent extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    /** The kind of the event ({@code model.added}, {@code setting.changed}...), as {@link ChatMessage#getEventKind()}. */
    @Column(nullable = false, length = 64)
    private String kind;

    @Column(nullable = false)
    private Date occurredAt;

    /** The message it was delivered in, null while it waits. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivered_message_id")
    @CleanupReference(Policy.WEAK)
    private ChatMessage deliveredIn;

    /** Delivered, or dropped because it no longer matters (a model added and removed again). */
    @Column(nullable = false)
    private boolean handled;

    /** The event as the assistant is told it. */
    @ExtendedAttribute
    @Transient
    private String text;

    /** The data of the event (JSON), null for none. */
    @ExtendedAttribute
    @Transient
    private String payload;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Date getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Date occurredAt) {
        this.occurredAt = occurredAt;
    }

    public ChatMessage getDeliveredIn() {
        return deliveredIn;
    }

    public void setDeliveredIn(ChatMessage deliveredIn) {
        this.deliveredIn = deliveredIn;
    }

    public boolean isHandled() {
        return handled;
    }

    public void setHandled(boolean handled) {
        this.handled = handled;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
