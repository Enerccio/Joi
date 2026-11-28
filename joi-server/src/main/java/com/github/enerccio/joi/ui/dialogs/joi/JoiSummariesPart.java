package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.service.SummarizerService;
import com.github.enerccio.joi.domain.service.SummarizerService.JobInfo;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Kind;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.web.context.request.RequestAttributes;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Summaries tab: what she remembers of the past, newest first. Each card is one summary: its time (a day, a part of a
 * day, a week, a month, a year), why and by which model it was made, and its text. A summary a longer one covers is dimmed:
 * it is no longer in her prompt. Above the list is what the model writes now and what waits for it. A summary is deleted by its
 * button; the messages it covered are summarized again after the next pause.
 */
@Configurable
public class JoiSummariesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private SummaryService summaryService;

    @Autowired
    private SummarizerService summarizerService;

    private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private VerticalLayout layout;
    private Div jobs;
    private Div list;
    private Span empty;
    private Joi joi;
    private Registration watching;
    private int shown;

    @Override
    public J_L caption() {
        return J_L.LABEL_SUMMARIES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.MSG_SUMMARIES_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        jobs = new Div();
        jobs.addClassName(JoiCssStyles.SUMMARY_JOBS);
        empty = new Span(loc.getValue(J_L.MSG_NO_SUMMARIES));
        empty.addClassName(CssStyles.HINT_TEXT);
        list = new Div();
        list.addClassNames(JoiCssStyles.CHAT_TRANSCRIPT_CONTENT);
        list.setWidthFull();
        layout = new VerticalLayout(hint, jobs, empty, list);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.addClassName(JoiCssStyles.SUMMARY_PANEL);
        layout.addAttachListener(e -> watch(e.getUI()));
        layout.addDetachListener(e -> unwatch());
        return layout;
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        reload();
        watch(UI.getCurrent());
    }

    @Override
    public void shown() throws Exception {
        reload();
    }

    private void watch(UI ui) {
        unwatch();
        if (joi == null || ui == null) {
            return;
        }
        try {
            // the change comes from the summary queue's thread: the refresh needs the request of the page for the session's user
            RequestAttributes request = ThreadCopyRequestAttributes.create();
            watching = summarizerService.watch(joi, () -> ui.access(() -> {
                try (InRequestScope _ = new InRequestScope(request)) {
                    reloadQuietly();
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

    private void reloadQuietly() {
        try {
            reload();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void reload() throws Exception {
        if (joi == null) {
            return;
        }
        List<Summary> all = summaryService.findAllOf(joi);
        Set<Long> inPrompt = SummaryCoverage.uncovered(all).stream().map(Summary::getId).collect(Collectors.toCollection(HashSet::new));
        list.removeAll();
        // the latest start first, and of the same start the longer one above the shorter ones it covers
        List<Summary> newestFirst = new ArrayList<>(all);
        newestFirst.sort(java.util.Comparator.comparing(Summary::getCoverFrom).reversed()
                .thenComparing(summary -> -summary.getSummaryType().getRank()));
        for (Summary summary : newestFirst) {
            list.add(card(summary, inPrompt.contains(summary.getId())));
        }
        shown = newestFirst.size();
        empty.setVisible(newestFirst.isEmpty());
        showJobs();
    }

    private void showJobs() {
        List<JobInfo> all = summarizerService.jobs(joi);
        jobs.setVisible(!all.isEmpty());
        jobs.removeAll();
        String running = all.stream().filter(JobInfo::running).map(this::describe).collect(Collectors.joining(", "));
        String waiting = all.stream().filter(j -> !j.running()).map(this::describe).collect(Collectors.joining(", "));
        if (!running.isEmpty()) {
            jobs.add(new Div(loc.getValue(J_L.LABEL_SUMMARY_JOBS_RUNNING) + ": " + running));
        }
        if (!waiting.isEmpty()) {
            jobs.add(new Div(loc.getValue(J_L.LABEL_SUMMARY_JOBS_QUEUED) + ": " + waiting));
        }
    }

    private String describe(JobInfo job) {
        J_L name = switch (job.kind()) {
            case WEEK -> J_L.ENUM_SUMMARY_TYPE_WEEK;
            case MONTH -> J_L.ENUM_SUMMARY_TYPE_MONTH;
            case YEAR -> J_L.ENUM_SUMMARY_TYPE_YEAR;
            default -> J_L.ENUM_SUMMARY_TYPE_DAY;
        };
        String text = loc.getValue(name);
        if (job.kind() == Kind.QUIET) {
            text += " (" + loc.getValue(J_L.LABEL_SUMMARY_ORIGIN_QUIET) + ")";
        } else if (job.kind() == Kind.AUTO || job.kind() == Kind.CONTEXT) {
            text += " (" + loc.getValue(J_L.LABEL_SUMMARY_ORIGIN_AUTO) + ")";
        } else if (job.kind() == Kind.MANUAL) {
            text += " (" + loc.getValue(J_L.LABEL_SUMMARY_ORIGIN_MANUAL) + ")";
        } else if (job.from() != null) {
            text += " " + format.format(job.from());
        }
        return text;
    }

    private Component card(Summary summary, boolean inPrompt) throws Exception {
        Div card = new Div();
        card.addClassName(JoiCssStyles.CHAT_MESSAGE);
        card.setClassName(JoiCssStyles.SUMMARY_COVERED, !inPrompt);

        Span kind = new Span(typeName(summary.getSummaryType()) + (summary.isPartial() ? " · " + loc.getValue(J_L.LABEL_SUMMARY_PARTIAL) : "")
                + " · " + summarizerService.period(joi, summary));
        kind.addClassName(JoiCssStyles.CHAT_EVENT_KIND);
        Markdown text = new Markdown(StringUtils.defaultString(summary.getSummary()));
        text.addClassName(JoiCssStyles.CHAT_MARKDOWN);

        List<String> meta = new ArrayList<>();
        if (summary.getOrigin() != null) {
            meta.add(origin(summary.getOrigin()));
        }
        if (summary.getMessageCount() != null) {
            meta.add(summary.getMessageCount() + " " + loc.getValue(J_L.LABEL_SUMMARY_FROM_MESSAGES));
        }
        if (StringUtils.isNotBlank(summary.getModelUsed())) {
            meta.add(summary.getModelUsed());
        }
        Span info = new Span(String.join(" · ", meta));
        info.addClassName(JoiCssStyles.CHAT_META);
        Button delete = new Button(Solid.TRASH.create(), e -> ConfirmDialog.show(loc.getValue(J_L.MSG_CONFIRM_DELETE_SUMMARY), () -> delete(summary)));
        delete.setThemeName("error tertiary small");
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        delete.setAriaLabel(loc.getValue(L.LABEL_DELETE));
        Div footer = new Div(info, delete);
        footer.addClassName(JoiCssStyles.CHAT_FOOTER);
        card.add(kind, text, footer);
        return card;
    }

    private String typeName(SummaryType type) {
        return loc.getValue(switch (type) {
            case ROLLING -> J_L.ENUM_SUMMARY_TYPE_ROLLING;
            case DAY -> J_L.ENUM_SUMMARY_TYPE_DAY;
            case WEEK -> J_L.ENUM_SUMMARY_TYPE_WEEK;
            case MONTH -> J_L.ENUM_SUMMARY_TYPE_MONTH;
            case YEAR -> J_L.ENUM_SUMMARY_TYPE_YEAR;
        });
    }

    private String origin(String origin) {
        return loc.getValue(switch (origin) {
            case "quiet" -> J_L.LABEL_SUMMARY_ORIGIN_QUIET;
            case "auto" -> J_L.LABEL_SUMMARY_ORIGIN_AUTO;
            case "manual" -> J_L.LABEL_SUMMARY_ORIGIN_MANUAL;
            default -> J_L.LABEL_SUMMARY_ORIGIN_ROLL;
        });
    }

    private void delete(Summary summary) {
        try {
            summaryService.delete(summary, false);
            reload();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // for tests

    /** How many summaries are shown. */
    public int getShown() {
        return shown;
    }

    /** The cards of the summaries, newest first. */
    public Div getList() {
        return list;
    }
}
