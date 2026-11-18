package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.*;

import java.util.Date;

/**
 * One message of the transcript of a {@link JoiTask}, as in a chat completion: the instructions, the model's turns
 * with their reasoning and tool calls, and the tool results. In order by {@link #getOrdinal()}.
 */
@Entity
@Table(name = "joi_task_messages")
public class JoiTaskMessage extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private JoiTask task;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskMessageRole role = TaskMessageRole.ASSISTANT;

    @ExtendedAttribute
    @Transient
    private String content;

    @ExtendedAttribute
    @Transient
    private String reasoning;

    /** The tool calls of an assistant message (JSON), null for none. */
    @ExtendedAttribute
    @Transient
    private String toolCalls;

    /** The call a tool message answers. */
    @ExtendedAttribute
    @Transient
    private String toolCallId;

    @ExtendedAttribute
    @Transient
    private String modelUsed;

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

    @ExtendedAttribute
    @Transient
    private Date responseEnd;

    public JoiTask getTask() {
        return task;
    }

    public void setTask(JoiTask task) {
        this.task = task;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public TaskMessageRole getRole() {
        return role;
    }

    public void setRole(TaskMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
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

    public Date getResponseEnd() {
        return responseEnd;
    }

    public void setResponseEnd(Date responseEnd) {
        this.responseEnd = responseEnd;
    }
}
