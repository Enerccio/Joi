package com.github.enerccio.joi.ui.jobs;

import com.github.enerccio.joi.domain.chat.ChatService;
import com.github.enerccio.joi.domain.collections.JoiTaskStatus;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.JoiTask;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.JoiTaskService;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.shared.Registration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * What each assistant does now, for the administrator, on {@code /jobs} under the jobs that run: her turn that runs or waits for the
 * one before it, her summary jobs (running or waiting in the queue of their model) and her tasks (running, queued, or waiting for the
 * user's answer). The jobs above it say which requests are in flight; this says whose work they are. Refreshed every two seconds.
 */
@Configurable
public class AssistantsRunsGrid extends VerticalLayout {

    private static final int POLL_MILLIS = 2000;

    /** One line: whose, what, in which state, since when. */
    record Activity(String user, String assistant, String what, String state, Date since) {
        String shown() {
            return String.join("\u0001", user, assistant, what, state, since == null ? "" : String.valueOf(since.getTime() / 1000));
        }
    }

    @Autowired
    private Localization loc;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SummarizerService summarizerService;

    @Autowired
    private JoiService joiService;

    @Autowired
    private JoiTaskService taskService;

    private final Grid<Activity> grid = new Grid<>();
    private final Span summary = new Span();
    private String shown;
    private int previousPollInterval = -1;
    private Registration pollRegistration;

    public AssistantsRunsGrid() {
        setSizeFull();
        setPadding(false);
    }

    /** Builds the components; call once after construction. */
    public AssistantsRunsGrid create() {
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setAllRowsVisible(true);
        grid.addColumn(Activity::user).setHeader(loc.getValue(L.LABEL_USER)).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Activity::assistant).setHeader(loc.getValue(J_L.LABEL_ASSISTANT)).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Activity::what).setHeader(loc.getValue(J_L.LABEL_ACTIVITY_WHAT)).setFlexGrow(1);
        grid.addColumn(Activity::state).setHeader(loc.getValue(J_L.LABEL_ACTIVITY_STATE)).setFlexGrow(1);
        grid.addColumn(activity -> activity.since() == null ? "" : DateFormat.getTimeInstance(DateFormat.MEDIUM).format(activity.since()))
                .setHeader(loc.getValue(L.LABEL_JOB_STARTED)).setAutoWidth(true).setFlexGrow(0);
        summary.addClassName(CssStyles.TEXT_SECONDARY);
        add(summary, grid);
        refresh();
        return this;
    }

    /** The activity of all the assistants, the oldest first; the turns first, then the summary jobs and the tasks of each assistant. */
    List<Activity> activities() throws Exception {
        List<Activity> rows = new ArrayList<>();
        for (ChatService.RunningTurn turn : chatService.running()) {
            rows.add(new Activity(login(turn.joi()), joiService.name(turn.joi()), loc.getValue(J_L.ACTIVITY_TURN),
                    turn.step() == null ? loc.getValue(J_L.ACTIVITY_TURN_WAITS) : loc.getValue(J_L.ACTIVITY_RUNNING)
                            + " · " + turn.step().name().toLowerCase().replace('_', ' '), turn.since()));
        }
        for (Joi joi : joiService.findAll()) {
            String assistant = joiService.name(joi);
            for (SummarizerService.JobInfo job : summarizerService.jobs(joi)) {
                rows.add(new Activity(login(joi), assistant, String.format(loc.getValue(J_L.ACTIVITY_SUMMARY), job.kind().name().toLowerCase()
                        .replace('_', ' ')), job.running() ? loc.getValue(J_L.ACTIVITY_RUNNING) : loc.getValue(J_L.ACTIVITY_QUEUED), job.from()));
            }
            Collection<JoiTaskStatus> open = List.of(JoiTaskStatus.QUEUED, JoiTaskStatus.RUNNING, JoiTaskStatus.WAITING);
            for (JoiTask task : taskService.findByStatus(joi, open)) {
                String state = switch (task.getStatus()) {
                    case RUNNING -> loc.getValue(J_L.ACTIVITY_RUNNING);
                    case WAITING -> loc.getValue(J_L.ACTIVITY_WAITS_FOR_YOU);
                    default -> loc.getValue(J_L.ACTIVITY_QUEUED);
                };
                rows.add(new Activity(login(joi), assistant, String.format(loc.getValue(J_L.ACTIVITY_TASK), task.getTool()), state,
                        task.getStartedAt()));
            }
        }
        // the turns are in the order they came; a stable sort by the assistant would hide which one is first
        rows.sort(Comparator.comparing(Activity::user).thenComparing(Activity::assistant));
        return rows;
    }

    private String login(Joi joi) {
        return joi.getOwner() == null ? "" : joi.getOwner().getLogin();
    }

    public void refresh() {
        try {
            List<Activity> rows = activities();
            String now = String.join("\u0002", rows.stream().map(Activity::shown).toList());
            if (now.equals(shown)) {
                return;
            }
            shown = now;
            grid.setItems(rows);
            summary.setText(rows.isEmpty() ? loc.getValue(J_L.MSG_ASSISTANTS_IDLE) : "");
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        previousPollInterval = ui.getPollInterval();
        ui.setPollInterval(previousPollInterval > 0 ? Math.min(previousPollInterval, POLL_MILLIS) : POLL_MILLIS);
        pollRegistration = ui.addPollListener(_ -> refresh());
        refresh();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
            detachEvent.getUI().setPollInterval(previousPollInterval);
        }
        super.onDetach(detachEvent);
    }
}
