package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import com.github.enerccio.marginalia.domain.traits.Fulltextable;
import jakarta.persistence.*;

import java.util.Date;

/**
 * A call to inference of its own that the assistant starts with a tool call (a {@code delegate} to the heavy model,
 * say). It runs after the reply that started it is finished, so she answers first ("I'm on it") and the GPU is not
 * asked for two models at once; its transcript is {@link JoiTaskMessage}s, and its result comes back to the
 * conversation as an event (see {@code DESIGN.md} → Tasks after the reply).
 */
@Entity
@Table(name = "joi_tasks")
public class JoiTask extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    /** The message whose response started the task, null when the user started it from the UI. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_message_id")
    @CleanupReference(Policy.WEAK)
    private ChatMessage originMessage;

    /** The event message that reported the result to the conversation, null until then. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_message_id")
    @CleanupReference(Policy.WEAK)
    private ChatMessage resultMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JoiTaskStatus status = JoiTaskStatus.QUEUED;

    /** The model role the task runs on. */
    @ExtendedAttribute
    @Transient
    private TextLLMType role;

    /** A short name of the task, for the UI and the event. */
    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String title;

    /** What the assistant asked for, with the context she gave. */
    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String instructions;

    /** The function that started it: a server tool's ({@code web_search}, {@code fetch_page}) or an extension's task. */
    @ExtendedAttribute
    @Transient
    private String tool;

    /** The page to read, for {@code fetch_page}. */
    @ExtendedAttribute
    @Transient
    private String url;

    /** The arguments of her call, as JSON, for a task of an extension (it reads them back when the task runs). */
    @ExtendedAttribute
    @Transient
    private String arguments;

    /** What the tool does, in a line, as it was when the task was made: an extension's tool may be gone when the task is shown. */
    @ExtendedAttribute
    @Transient
    private String toolDescription;

    /** Whether the user's standing approval of the tool approved it (it still went through approval, as the API does it). */
    @ExtendedAttribute
    @Transient
    private Boolean autoApproved;

    /** The id of the tool call that started the task, to answer it. */
    @ExtendedAttribute
    @Transient
    private String toolCallId;

    @ExtendedAttribute
    @Fulltextable
    @Transient
    private String result;

    @ExtendedAttribute
    @Transient
    private String error;

    @ExtendedAttribute
    @Transient
    private Date startedAt;

    @ExtendedAttribute
    @Transient
    private Date finishedAt;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public ChatMessage getOriginMessage() {
        return originMessage;
    }

    public void setOriginMessage(ChatMessage originMessage) {
        this.originMessage = originMessage;
    }

    public ChatMessage getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(ChatMessage resultMessage) {
        this.resultMessage = resultMessage;
    }

    public JoiTaskStatus getStatus() {
        return status;
    }

    public void setStatus(JoiTaskStatus status) {
        this.status = status;
    }

    public TextLLMType getRole() {
        return role;
    }

    public void setRole(TextLLMType role) {
        this.role = role;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getToolDescription() {
        return toolDescription;
    }

    public void setToolDescription(String toolDescription) {
        this.toolDescription = toolDescription;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isAutoApproved() {
        return Boolean.TRUE.equals(autoApproved);
    }

    public void setAutoApproved(boolean autoApproved) {
        this.autoApproved = autoApproved ? Boolean.TRUE : null;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Date getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Date startedAt) {
        this.startedAt = startedAt;
    }

    public Date getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Date finishedAt) {
        this.finishedAt = finishedAt;
    }
}
