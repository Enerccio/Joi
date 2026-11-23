package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.collections.TaskMessageRole;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.model.impl.JoiTaskMessage;
import com.github.enerccio.joi.domain.service.JoiTaskMessageService;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.dialogs.AutoApprovedDialog;
import com.github.enerccio.joi.ui.tools.ToolPermissionsPanel;
import com.github.enerccio.joi.ui.widgets.LongText;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.dom.Element;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.web.context.request.RequestAttributes;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The Tasks tab: what she started with her tools (web search, reading a page). A task that waits for the user is
 * approved or denied here (the same as the API's), "Approve all of this tool" approves it and every later one without asking
 * (listed under Auto-approved, where it is taken back), a task that has not ended is cancelled, and the selected task shows
 * what was asked, what it found or why it failed, and its transcript. Follows the tasks live. The assistant's own
 * permissions of the tools are above the list.
 */
@Configurable
public class JoiTasksPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private WebTaskService tasks;

    @Autowired
    private JoiTaskMessageService transcripts;

    private static final int TOOL_TEXT = 3000;

    private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private final ToolPermissionsPanel permissions = new ToolPermissionsPanel(ToolScope.JOI);
    private Joi joi;
    private Grid<JoiTask> grid;
    private final List<JoiTask> items = new ArrayList<>();
    private Button approveButton;
    private Button approveAllButton;
    private Button denyButton;
    private Button cancelButton;
    private Div details;
    private Registration watching;

    @Override
    public J_L caption() {
        return J_L.LABEL_TASKS;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.HELP_TASKS));
        hint.addClassName(CssStyles.HINT_TEXT);
        Details tools = new Details(loc.getValue(J_L.LABEL_TOOL_PERMISSIONS), permissions.create());
        tools.setWidthFull();

        approveButton = new Button(loc.getValue(J_L.LABEL_TASK_APPROVE), Solid.CHECK.create(), e -> approve());
        approveButton.setThemeName("primary");
        approveAllButton = new Button(loc.getValue(J_L.LABEL_TASK_APPROVE_ALL), Solid.CHECK_DOUBLE.create(), e -> approveAll());
        denyButton = new Button(loc.getValue(J_L.LABEL_TASK_DENY), Solid.BAN.create(), e -> deny());
        cancelButton = new Button(loc.getValue(L.LABEL_CANCEL), Solid.STOP.create(), e ->
                ConfirmDialog.show(loc.getValue(J_L.MSG_CONFIRM_CANCEL_TASK), this::cancel));
        Button autoApproved = new Button(loc.getValue(J_L.LABEL_AUTO_APPROVED), Solid.LIST.create(), e -> showAutoApproved());
        HorizontalLayout buttons = new HorizontalLayout(approveButton, approveAllButton, denyButton, cancelButton, autoApproved);
        buttons.setWidthFull();
        buttons.setWrap(true);

        grid = new Grid<>();
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.addColumn(JoiTask::getTitle).setHeader(loc.getValue(J_L.LABEL_TASK_TITLE)).setFlexGrow(1);
        grid.addColumn(this::status).setHeader(loc.getValue(J_L.LABEL_TASK_STATUS)).setFlexGrow(0).setWidth("19rem");
        grid.addColumn(task -> task.getCreation() == null ? "" : format.format(task.getCreation()))
                .setHeader(loc.getValue(J_L.LABEL_TASK_CREATED)).setFlexGrow(0).setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.setEmptyStateText(loc.getValue(J_L.MSG_NO_TASKS));
        grid.setHeight("14rem");
        grid.addSelectionListener(e -> selected());

        details = new Div();
        details.addClassName(JoiCssStyles.TASK_DETAILS);

        VerticalLayout layout = new VerticalLayout(hint, tools, buttons, grid, details);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, details);
        layout.addAttachListener(e -> watch(e.getUI()));
        layout.addDetachListener(e -> unwatch());
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        if (joi.getId() != null) {
            permissions.load(joi);
            refresh();
        }
    }

    @Override
    public void shown() throws Exception {
        refresh();
    }

    private void watch(UI ui) {
        unwatch();
        if (joi == null || joi.getId() == null) {
            return;
        }
        try {
            // the change comes from another thread: the refresh needs the request of the page for the session's user
            RequestAttributes request = ThreadCopyRequestAttributes.create();
            watching = tasks.watch(joi, () -> ui.access(() -> {
                try (InRequestScope _ = new InRequestScope(request)) {
                    refresh();
                } catch (Exception e) {
                    UIUtils.internalServerError(loc, e);
                }
            }));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void unwatch() {
        if (watching != null) {
            watching.unregister();
            watching = null;
        }
    }

    private String status(JoiTask task) {
        String text = loc.getValue(switch (task.getStatus()) {
            case QUEUED -> J_L.LABEL_TASK_STATUS_QUEUED;
            case RUNNING -> J_L.LABEL_TASK_STATUS_RUNNING;
            case WAITING -> J_L.LABEL_TASK_STATUS_WAITING;
            case DONE -> J_L.LABEL_TASK_STATUS_DONE;
            case FAILED -> J_L.LABEL_TASK_STATUS_FAILED;
            case CANCELLED -> J_L.LABEL_TASK_STATUS_CANCELLED;
        });
        return task.isAutoApproved() ? text + " (" + loc.getValue(J_L.LABEL_TASK_AUTO) + ")" : text;
    }

    /** Reads the tasks again, keeping the selected one. */
    void refresh() throws Exception {
        if (joi == null || joi.getId() == null || grid == null) {
            return;
        }
        String selected = grid.getSelectedItems().stream().findFirst().map(JoiTask::getUuid).orElse(null);
        items.clear();
        items.addAll(tasks.tasks(joi));
        grid.setItems(items);
        if (selected != null) {
            items.stream().filter(t -> t.getUuid().equals(selected)).findFirst().ifPresent(grid::select);
        }
        selected();
    }

    private JoiTask selection() {
        return grid.getSelectedItems().stream().findFirst().orElse(null);
    }

    private void selected() {
        JoiTask task = selection();
        JoiTaskStatus status = task == null ? null : task.getStatus();
        boolean waiting = status == JoiTaskStatus.WAITING;
        approveButton.setEnabled(waiting);
        approveAllButton.setEnabled(waiting && ServerTool.of(task.getTool()) != null);
        denyButton.setEnabled(waiting);
        cancelButton.setEnabled(status != null && !status.isFinished());
        details.removeAll();
        if (task == null) {
            return;
        }
        try {
            List<JoiTaskMessage> steps = transcripts.findByTask(task);
            details.add(meta(task, steps));
            details.add(block(J_L.LABEL_TASK_QUESTION, new Pre(StringUtils.defaultString(task.getInstructions()))));
            if (StringUtils.isNotBlank(task.getUrl())) {
                details.add(block(J_L.LABEL_TASK_URL, new Pre(task.getUrl())));
            }
            if (StringUtils.isNotBlank(task.getResult())) {
                Markdown result = new Markdown(task.getResult());
                result.addClassNames(JoiCssStyles.CHAT_MARKDOWN, JoiCssStyles.TASK_RESULT);
                details.add(block(J_L.LABEL_TASK_RESULT, result));
            }
            if (StringUtils.isNotBlank(task.getError())) {
                Pre error = new Pre(task.getError());
                error.addClassName(JoiCssStyles.TASK_ERROR);
                details.add(block(J_L.LABEL_TASK_ERROR, error));
            }
            details.add(transcript(task, steps));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** One line of facts: the tool, the model, when it ran and for how long, and the tokens it took. */
    private Component meta(JoiTask task, List<JoiTaskMessage> steps) {
        List<String> facts = new ArrayList<>();
        if (StringUtils.isNotBlank(task.getTool())) {
            facts.add(loc.getValue(J_L.LABEL_TASK_TOOL) + ": " + task.getTool());
        }
        steps.stream().map(JoiTaskMessage::getModelUsed).filter(StringUtils::isNotBlank).findFirst().ifPresent(facts::add);
        if (task.getStartedAt() != null) {
            facts.add(format.format(task.getStartedAt()));
            if (task.getFinishedAt() != null) {
                long seconds = Math.max(0, (task.getFinishedAt().getTime() - task.getStartedAt().getTime()) / 1000);
                facts.add(loc.getValue(J_L.LABEL_TASK_DURATION) + " " + (seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s"));
            }
        }
        long tokens = steps.stream().map(JoiTaskMessage::getTokenCount).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
        if (tokens > 0) {
            facts.add(tokens + " " + loc.getValue(J_L.LABEL_TASK_TOKENS));
        }
        Div line = new Div(new Text(String.join(" · ", facts)));
        line.addClassName(JoiCssStyles.TASK_META);
        return line;
    }

    /** A block of the details under a small heading. */
    private Component block(J_L label, Component content) {
        H5 heading = new H5(loc.getValue(label));
        Div block = new Div(heading, content);
        block.addClassName(JoiCssStyles.TASK_BLOCK);
        return block;
    }

    /**
     * The transcript folded away (open while the task goes on): what the model was told once, then each step of the model with
     * its reasoning, its words and the tools it called, each with what it answered.
     */
    private Component transcript(JoiTask task, List<JoiTaskMessage> steps) {
        Div body = new Div();
        if (steps.isEmpty()) {
            Span none = new Span(loc.getValue(J_L.MSG_TASK_NO_TRANSCRIPT));
            none.addClassName(CssStyles.HINT_TEXT);
            body.add(none);
        }
        // the answers of the tools, by the call they answer
        java.util.Map<String, JoiTaskMessage> answers = new java.util.HashMap<>();
        for (JoiTaskMessage step : steps) {
            if (step.getRole() == TaskMessageRole.TOOL && step.getToolCallId() != null) {
                answers.put(step.getToolCallId(), step);
            }
        }
        List<String> told = new ArrayList<>();
        for (JoiTaskMessage step : steps) {
            if ((step.getRole() == TaskMessageRole.SYSTEM || step.getRole() == TaskMessageRole.USER) && StringUtils.isNotBlank(step.getContent())) {
                told.add(step.getContent());
            }
        }
        if (!told.isEmpty()) {
            Details instructions = new Details(loc.getValue(J_L.LABEL_TASK_INSTRUCTIONS), new Pre(String.join("\n\n", told)));
            instructions.addClassName(JoiCssStyles.CHAT_REASONING);
            body.add(instructions);
        }
        int number = 0;
        for (JoiTaskMessage step : steps) {
            if (step.getRole() != TaskMessageRole.ASSISTANT) {
                continue;
            }
            body.add(round(++number, step, answers));
        }
        Details transcript = new Details(loc.getValue(J_L.LABEL_TASK_TRANSCRIPT) + " (" + number + ")", body);
        transcript.setOpened(!task.getStatus().isFinished());
        transcript.setWidthFull();
        return transcript;
    }

    /** One step of the model, and the answers of the tools it called. */
    private Component round(int number, JoiTaskMessage step, java.util.Map<String, JoiTaskMessage> answers) {
        Div round = new Div();
        round.addClassName(JoiCssStyles.TASK_STEP);
        List<String> head = new ArrayList<>();
        head.add(loc.getValue(J_L.LABEL_TASK_STEP) + " " + number);
        if (StringUtils.isNotBlank(step.getModelUsed())) {
            head.add(step.getModelUsed());
        }
        if (step.getTokenCount() != null) {
            head.add(step.getTokenCount() + " " + loc.getValue(J_L.LABEL_TASK_TOKENS));
        }
        Div title = new Div(new Text(String.join(" · ", head)));
        title.addClassName(JoiCssStyles.TASK_STEP_HEAD);
        round.add(title);
        if (StringUtils.isNotBlank(step.getReasoning())) {
            Markdown reasoning = new Markdown(step.getReasoning());
            reasoning.addClassName(JoiCssStyles.CHAT_MARKDOWN);
            Details reasons = new Details(loc.getValue(J_L.LABEL_REASONING), reasoning);
            reasons.addClassName(JoiCssStyles.CHAT_REASONING);
            round.add(reasons);
        }
        if (StringUtils.isNotBlank(step.getContent())) {
            Markdown words = new Markdown(step.getContent());
            words.addClassName(JoiCssStyles.CHAT_MARKDOWN);
            round.add(words);
        }
        round.add(calls(step, answers));
        return round;
    }

    /** The tools a step called: each one's name and arguments, and under them what it answered. */
    private Component calls(JoiTaskMessage step, java.util.Map<String, JoiTaskMessage> answers) {
        Div list = new Div();
        if (StringUtils.isBlank(step.getToolCalls())) {
            return list;
        }
        try {
            for (JsonElement element : JsonParser.parseString(step.getToolCalls()).getAsJsonArray()) {
                JsonObject call = element.getAsJsonObject();
                Div entry = new Div();
                entry.addClassName(JoiCssStyles.CHAT_TOOL);
                Element name = new Element("b");
                name.setText(call.has("name") ? call.get("name").getAsString() : "?");
                entry.getElement().appendChild(name);
                String arguments = call.has("arguments") && !call.get("arguments").isJsonNull() ? call.get("arguments").getAsString().strip() : "";
                if (!arguments.isEmpty() && !"{}".equals(arguments)) {
                    entry.add(data(arguments));
                }
                JoiTaskMessage answer = call.has("id") ? answers.get(call.get("id").getAsString()) : null;
                if (answer != null && StringUtils.isNotBlank(answer.getContent())) {
                    entry.add(data("→ " + answer.getContent()));
                }
                list.add(entry);
            }
        } catch (RuntimeException e) {
            // calls that cannot be read are shown as they were stored
            list.add(data(step.getToolCalls()));
        }
        return list;
    }

    private Component data(String text) {
        return new LongText(text, TOOL_TEXT, loc.getValue(J_L.LABEL_SHOW_ALL));
    }

    private void approve() {
        run(() -> tasks.approve(selection()));
    }

    private void approveAll() {
        JoiTask task = selection();
        ServerTool tool = task == null ? null : ServerTool.of(task.getTool());
        if (tool != null) {
            ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_APPROVE_ALL), tool.function()),
                    () -> run(() -> tasks.autoApprove(joi, tool, true)));
        }
    }

    private void deny() {
        JoiTask task = selection();
        if (task == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(loc.getValue(J_L.LABEL_TASK_DENY));
        dialog.setWidth("min(32em, 95vw)");
        TextField reason = new TextField(loc.getValue(J_L.LABEL_TASK_DENY_REASON));
        reason.setWidthFull();
        dialog.add(reason);
        Button cancel = new Button(loc.getValue(L.LABEL_CANCEL), e -> dialog.close());
        Button deny = new Button(loc.getValue(J_L.LABEL_TASK_DENY), e -> {
            dialog.close();
            run(() -> tasks.deny(task, reason.getValue()));
        });
        deny.setThemeName("primary");
        dialog.getFooter().add(cancel, deny);
        DialogFocus.onOpen(dialog, reason);
        dialog.open();
    }

    private void cancel() {
        run(() -> tasks.cancel(selection()));
    }

    private void showAutoApproved() {
        try {
            AutoApprovedDialog dialog = new AutoApprovedDialog(joi);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private void run(Action action) {
        try {
            action.run();
            refresh();
        } catch (IllegalStateException e) {
            // answered meanwhile (the API, another window): show what it is now
            try {
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // for tests

    public Grid<JoiTask> getGrid() {
        return grid;
    }

    public Button getApproveButton() {
        return approveButton;
    }

    public Button getApproveAllButton() {
        return approveAllButton;
    }

    public Button getDenyButton() {
        return denyButton;
    }

    public Button getCancelButton() {
        return cancelButton;
    }

    public ToolPermissionsPanel getPermissions() {
        return permissions;
    }
}
