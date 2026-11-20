package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
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
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.DialogFocus;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
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
            watching = tasks.watch(joi, () -> ui.access(() -> {
                try {
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
            addSection(J_L.LABEL_TASK_QUESTION, task.getInstructions());
            addSection(J_L.LABEL_TASK_URL, task.getUrl());
            addSection(J_L.LABEL_TASK_RESULT, task.getResult());
            addSection(J_L.LABEL_TASK_ERROR, task.getError());
            List<JoiTaskMessage> steps = transcripts.findByTask(task);
            details.add(new H5(loc.getValue(J_L.LABEL_TASK_TRANSCRIPT)));
            if (steps.isEmpty()) {
                Span none = new Span(loc.getValue(J_L.MSG_TASK_NO_TRANSCRIPT));
                none.addClassName(CssStyles.HINT_TEXT);
                details.add(none);
            }
            for (JoiTaskMessage step : steps) {
                String text = StringUtils.defaultIfEmpty(step.getContent(), StringUtils.defaultString(step.getToolCalls()));
                Pre line = new Pre(step.getRole().name().toLowerCase() + ": " + text);
                line.addClassName(JoiCssStyles.TASK_STEP);
                details.add(line);
            }
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void addSection(J_L label, String text) {
        if (StringUtils.isNotBlank(text)) {
            details.add(new H5(loc.getValue(label)), new Pre(text));
        }
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
