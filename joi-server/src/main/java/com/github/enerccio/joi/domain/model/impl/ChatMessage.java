package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.ChatMessageType;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import com.github.enerccio.marginalia.domain.traits.Fulltextable;
import jakarta.persistence.*;

import java.util.Date;

/**
 * One exchange of the conversation, like a part of Marginalia: the input (what the user said, or the event that
 * happened, see {@link ChatMessageType}) and the assistant's response with her reasoning. The prompt that was sent for
 * it is a {@link MessagePrompt}, stored apart.
 * <p>
 * Columns are what the prompt building and the summaries query: the assistant, the type, when it was said (summaries
 * cover a time, not a list of messages), whether it is pinned (kept verbatim in the prompt) or private (only for local
 * models). The rest is extended attributes.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatMessageType type = ChatMessageType.USER;

    /** When the input came: the time summaries cover. */
    @Column(nullable = false)
    private Date sentAt;

    /** Kept verbatim in the prompt (a promise, an exact instruction), never compacted into a summary. */
    @Column(nullable = false)
    private boolean pinned;

    /** Said in privacy mode: put only into prompts for local models, left out of roll-ups for external ones. */
    @Column(nullable = false)
    private boolean privateMode;

    /** The user's words, or the event as the assistant is told it. */
    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String input;

    /** The kind of the event ({@code node.connected}, {@code timer}...), null for the user's words. */
    @ExtendedAttribute
    @Transient
    private String eventKind;

    /** The node the input came from, null when it did not come from one (the UI). */
    @ExtendedAttribute
    @Transient
    private String node;

    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String response;

    /** The thinking of the model before the response, null when it did not think. */
    @ExtendedAttribute
    @Transient
    private String responseReasoning;

    /** The response as the model wrote it, before the user edited it; null when not edited. */
    @ExtendedAttribute
    @Transient
    private String originalResponse;

    /** The tool calls of the response and their results (JSON), null for none. */
    @ExtendedAttribute
    @Transient
    private String toolCalls;

    /** The mood she was in. */
    @ExtendedAttribute
    @Transient
    private String mood;

    @ExtendedAttribute
    @Transient
    private String modelUsed;

    @ExtendedAttribute
    @Transient
    private String protocolUsed;

    /** Whether the model that answered is outside the house (privacy: which provider saw the turn). */
    @ExtendedAttribute
    @Transient
    private Boolean externalModel;

    @ExtendedAttribute
    @Transient
    private Long promptTokens;

    @ExtendedAttribute
    @Transient
    private Long tokenCount;

    @ExtendedAttribute
    @Transient
    private Long tokenReasoningCount;

    @ExtendedAttribute
    @Transient
    private Date request;

    /** The first token of the response (or of the reasoning). */
    @ExtendedAttribute
    @Transient
    private Date ttft;

    @ExtendedAttribute
    @Transient
    private Date reasoningEnd;

    @ExtendedAttribute
    @Transient
    private Date responseEnd;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public ChatMessageType getType() {
        return type;
    }

    public void setType(ChatMessageType type) {
        this.type = type;
    }

    public Date getSentAt() {
        return sentAt;
    }

    public void setSentAt(Date sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getEventKind() {
        return eventKind;
    }

    public void setEventKind(String eventKind) {
        this.eventKind = eventKind;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getResponseReasoning() {
        return responseReasoning;
    }

    public void setResponseReasoning(String responseReasoning) {
        this.responseReasoning = responseReasoning;
    }

    public String getOriginalResponse() {
        return originalResponse;
    }

    public void setOriginalResponse(String originalResponse) {
        this.originalResponse = originalResponse;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public String getProtocolUsed() {
        return protocolUsed;
    }

    public void setProtocolUsed(String protocolUsed) {
        this.protocolUsed = protocolUsed;
    }

    public Boolean getExternalModel() {
        return externalModel;
    }

    public void setExternalModel(Boolean externalModel) {
        this.externalModel = externalModel;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Long tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Long getTokenReasoningCount() {
        return tokenReasoningCount;
    }

    public void setTokenReasoningCount(Long tokenReasoningCount) {
        this.tokenReasoningCount = tokenReasoningCount;
    }

    public Date getRequest() {
        return request;
    }

    public void setRequest(Date request) {
        this.request = request;
    }

    public Date getTtft() {
        return ttft;
    }

    public void setTtft(Date ttft) {
        this.ttft = ttft;
    }

    public Date getReasoningEnd() {
        return reasoningEnd;
    }

    public void setReasoningEnd(Date reasoningEnd) {
        this.reasoningEnd = reasoningEnd;
    }

    public Date getResponseEnd() {
        return responseEnd;
    }

    public void setResponseEnd(Date responseEnd) {
        this.responseEnd = responseEnd;
    }
}
