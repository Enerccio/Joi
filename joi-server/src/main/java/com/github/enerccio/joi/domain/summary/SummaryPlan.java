package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.summary.SummaryDays.Span;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What is to be summarized for an assistant now, decided from her summaries and the messages no summary covers. Pure:
 * it reads no clock and no database, so every rule is tested on its own (see {@code SummaryPlanTest}).
 * <p>
 * The rules:
 * <ul>
 *   <li><b>A pause</b> ({@link Kind#QUIET}): nobody wrote for the pause time and no task of hers is open (its result is
 *   still to come into the conversation): the messages since the last summary are summarized. A day can have several
 *   such summaries, the parts of the day.</li>
 *   <li><b>A growing conversation</b> ({@link Kind#AUTO}): the unsummarized messages passed the tokens she is allowed
 *   to keep: the oldest of them are summarized.</li>
 *   <li><b>A day that ended</b> ({@link Kind#DAY_ROLL}): what is left of it is summarized and its parts become one day
 *   summary.</li>
 *   <li><b>A week, a month or a year that ended</b>: the summaries of its time become one, at the user's time of day,
 *   and only when a day summary of the time after it exists, so there is always at least one day summary and the
 *   week that is going on is never summarized. A longer time waits for the shorter ones inside it.</li>
 * </ul>
 * A message that only answers a summary she was told about ({@link Unit#noise()}) never makes a summary of its own: it
 * would be an endless chain of summaries of the reactions to summaries.
 */
public final class SummaryPlan {

    private SummaryPlan() {
    }

    /** What a job summarizes. */
    public enum Kind {
        /** A day that ended: its last messages are summarized and its parts rolled into one. */
        DAY_ROLL(0, 0),
        /** A pause in the conversation. */
        QUIET(0, 1),
        /** The unsummarized messages grew too long. */
        AUTO(0, 1),
        /**
         * Her turn found that her history would not fit her chat model's context: the oldest are summarized now, from inside the
         * turn (it waits for them, then tells them to her with the words of the turn). Never planned, the turn asks for it.
         */
        CONTEXT(0, 0),
        /** The user asked for it, up to a message. */
        MANUAL(0, 1),
        WEEK(1, 0),
        MONTH(2, 0),
        YEAR(3, 0);

        private final int rank;
        private final int priority;

        Kind(int rank, int priority) {
            this.rank = rank;
            this.priority = priority;
        }

        /** The order the jobs of one assistant run in: the days, then the weeks, the months, the years. */
        public int rank() {
            return rank;
        }

        /** Within a rank, the lower runs first: the days that ended before the summaries of what is going on now. */
        public int priority() {
            return priority;
        }

        /** The length of time a period job covers, null for the others. */
        public SummaryType periodType() {
            return switch (this) {
                case WEEK -> SummaryType.WEEK;
                case MONTH -> SummaryType.MONTH;
                case YEAR -> SummaryType.YEAR;
                default -> null;
            };
        }
    }

    /**
     * A job. {@code from} and {@code to} are the time of a day or a period; for {@link Kind#MANUAL} {@code to} is the
     * last message to summarize; the others have no time of their own (null).
     */
    public record Job(Kind kind, Instant from, Instant to) {

        /** Two jobs with the same key are the same job: asked again, it is queued once. */
        public String key() {
            return switch (kind) {
                case QUIET, AUTO, CONTEXT -> kind.name();
                case MANUAL -> kind.name() + ":" + to;
                default -> kind.name() + ":" + from + ":" + to;
            };
        }
    }

    /** The settings of the rules, resolved along the chain. */
    public record Settings(int quietMinutes, boolean auto, int autoTokens, LocalTime weeklyTime, LocalTime monthlyTime,
                           LocalTime yearlyTime) {

        /** The time of day the summary of the kind of period may be made. */
        LocalTime timeOf(Kind kind) {
            return switch (kind) {
                case WEEK -> weeklyTime;
                case MONTH -> monthlyTime;
                default -> yearlyTime;
            };
        }
    }

    /** A summary that exists. A part of a day is {@code partial}: it is made from messages and waits to be rolled into its day. */
    public record Cover(SummaryType type, Instant from, Instant to, boolean partial) {

        boolean isDayPart() {
            return type == SummaryType.DAY && partial;
        }
    }

    /** A message of the conversation no summary covers: when, how long (in tokens) and whether it only answers a summary. */
    public record Unit(Instant sentAt, int tokens, boolean noise) {
    }

    /** What the messages are covered up to: the end of the last summary made of them, the beginning of time for none. */
    public static Instant coveredUpTo(List<Cover> covers) {
        return covers.stream().filter(c -> c.type() != SummaryType.ROLLING).map(Cover::to).max(Comparator.naturalOrder())
                .orElse(Instant.MIN);
    }

    /**
     * @param units    the messages not covered yet, any order (a covered one is ignored)
     * @param waiting  a task of hers is open: its result has still to come into the conversation
     * @return the jobs due now, in the order they are to run: a day that ended, a pause, a growing conversation, then
     * the weeks, the months, the years
     */
    public static List<Job> plan(Instant now, Settings settings, SummaryDays days, List<Cover> covers, List<Unit> units, boolean waiting) {
        Instant upTo = coveredUpTo(covers);
        List<Unit> open = units.stream().filter(u -> !u.sentAt().isBefore(upTo)).sorted(Comparator.comparing(Unit::sentAt)).toList();
        List<Job> jobs = new ArrayList<>();

        // the days that ended and still have parts, or messages that nothing summarizes
        Set<Instant> dayStarts = new HashSet<>();
        covers.stream().filter(Cover::isDayPart).forEach(c -> dayStarts.add(days.spanOf(SummaryType.DAY, c.from()).from()));
        open.stream().filter(u -> !u.noise()).forEach(u -> dayStarts.add(days.spanOf(SummaryType.DAY, u.sentAt()).from()));
        dayStarts.stream().sorted().map(start -> days.spanOf(SummaryType.DAY, start)).filter(span -> !span.to().isAfter(now))
                .forEach(span -> jobs.add(new Job(Kind.DAY_ROLL, span.from(), span.to())));

        if (!open.isEmpty()) {
            Instant last = open.getLast().sentAt();
            boolean substance = open.stream().anyMatch(u -> !u.noise());
            if (settings.quietMinutes() > 0 && substance && !waiting
                    && !now.isBefore(last.plusSeconds(settings.quietMinutes() * 60L))) {
                jobs.add(new Job(Kind.QUIET, null, null));
            }
            long tokens = open.stream().mapToLong(Unit::tokens).sum();
            if (settings.auto() && settings.autoTokens() > 0 && tokens > settings.autoTokens() && open.size() > 1) {
                jobs.add(new Job(Kind.AUTO, null, null));
            }
        }

        // the time of a week, a month and a year that ended; the longest first would hide the shorter, so the shortest first
        for (Kind kind : List.of(Kind.WEEK, Kind.MONTH, Kind.YEAR)) {
            periods(now, settings, days, covers, kind, jobs);
        }
        return jobs;
    }

    private static void periods(Instant now, Settings settings, SummaryDays days, List<Cover> covers, Kind kind, List<Job> planned) {
        SummaryType type = kind.periodType();
        Instant earliest = covers.stream().map(Cover::from).min(Comparator.naturalOrder()).orElse(null);
        if (earliest == null) {
            return;
        }
        // a day summary of the time after the period always stays: the period is summarized only once one exists
        Instant newestDay = covers.stream().filter(c -> c.type() == SummaryType.DAY).map(Cover::from).max(Comparator.naturalOrder())
                .orElse(null);
        List<Job> found = new ArrayList<>();
        for (Span span = days.spanOf(type, earliest); !span.to().isAfter(now); span = days.next(span)) {
            Span period = span;
            if (now.isBefore(days.runAt(period.to(), settings.timeOf(kind))) || newestDay == null || newestDay.isBefore(period.to())) {
                continue;
            }
            boolean made = covers.stream().anyMatch(c -> c.type().getRank() >= type.getRank()
                    && !c.from().isAfter(period.from()) && !c.to().isBefore(period.to()));
            boolean hasSources = covers.stream().anyMatch(c -> c.type() != SummaryType.ROLLING && c.type().getRank() < type.getRank()
                    && !c.partial() && period.contains(c.from(), c.to()));
            // what is shorter and still to be made inside it comes first, and it is looked at again after
            boolean shorterWaits = planned.stream().anyMatch(job -> job.kind().rank() < kind.rank() && job.from() != null
                    && period.contains(job.from(), job.to()))
                    || found.stream().anyMatch(job -> job.kind().rank() < kind.rank() && period.contains(job.from(), job.to()));
            if (!made && hasSources && !shorterWaits) {
                found.add(new Job(kind, period.from(), period.to()));
            }
        }
        planned.addAll(found);
    }
}
