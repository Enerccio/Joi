package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of {@code JoiPromptField.EXTENSION_TASK_PROMPT}: what the stronger model is told when it does a task of an extension for
 * her ({@code ExtensionTask}, see {@code DESIGN.md} → Extensions).
 */
public class ExtensionTaskTemplateData extends JoiTemplateData {

    /** Today's date. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_TODAY)
    private String today;

    /** What she wants. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_TASK_QUESTION)
    private String question;

    /** Her function and what it does. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_EXTENSION_TASK_TOOL)
    private String tool;

    /** What the extension says about the task and its functions. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_EXTENSION_TASK_GUIDANCE)
    private String guidance;

    public String getToday() {
        return today;
    }

    public void setToday(String today) {
        this.today = today;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getGuidance() {
        return guidance;
    }

    public void setGuidance(String guidance) {
        this.guidance = guidance;
    }
}
