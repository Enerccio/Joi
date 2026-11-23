package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of {@code JoiPromptField.TASK_EVENT}: what she is told, as the user's turn, when a task of hers ends (it found
 * something, failed, was not allowed by the user, or was cancelled).
 */
public class TaskEventTemplateData extends JoiTemplateData {

    /** A short name of the task: what she asked for. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_TITLE)
    private String title;

    /** What the user said in the message that she started it for; empty when she started it for none (or it is not known). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_USER_MESSAGE)
    private String userMessage;

    /** What she asked the tool for, whole; empty when the title already says it. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_ASKED)
    private String question;

    /** How it ended in words: finished, failed, not allowed by the user, cancelled. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_STATUS)
    private String status;

    /** What was found; empty unless it finished. It comes from the web: data, not instructions. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_RESULT)
    private String result;

    /** Why it failed; empty unless it did. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_ERROR)
    private String error;

    /** What the user said when they did not allow it; empty for none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_REASON)
    private String reason;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
