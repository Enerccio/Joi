package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.collections.SummaryType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The words about a time a summary covers, for her prompt and for the events: the same summary always reads the same
 * (it never depends on the clock), so the prompt the model's cache keeps does not change by itself. Pure.
 */
public final class SummaryText {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", LOCALE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", LOCALE);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("MMMM d", LOCALE);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", LOCALE);

    /** A summary as the prompt shows it. */
    public record Entry(SummaryType type, boolean partial, Instant from, Instant to, String text) {
    }

    private final SummaryDays days;

    public SummaryText(SummaryDays days) {
        this.days = days;
    }

    private ZoneId zone() {
        return days.zone();
    }

    /** The time a summary covers, in words: a day, a part of a day with its times, a week, a month, a year. */
    public String period(SummaryType type, boolean partial, Instant from, Instant to) {
        switch (type) {
            case WEEK: {
                LocalDate first = days.dateOf(from);
                LocalDate last = days.dateOf(to.minusMillis(1));
                String start = first.getYear() == last.getYear() ? DAY_MONTH.format(first) : DATE.format(first);
                return start + " to " + DATE.format(last);
            }
            case MONTH:
                return MONTH.format(days.dateOf(from));
            case YEAR:
                return String.valueOf(days.dateOf(from).getYear());
            default:
                return partial || type == SummaryType.ROLLING ? span(from, to.minusMillis(1)) : DAY.format(days.dateOf(from));
        }
    }

    /** One moment, as a day and a time. */
    public String moment(Instant moment) {
        ZonedDateTime at = moment.atZone(zone());
        return DAY.format(at) + ", " + TIME.format(at);
    }

    /** From one moment to another, with the times; the date once when it is the same day. */
    public String span(Instant from, Instant to) {
        ZonedDateTime start = from.atZone(zone());
        ZonedDateTime end = to.atZone(zone());
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return DAY.format(start) + ", " + TIME.format(start) + " to " + TIME.format(end);
        }
        return DAY.format(start) + ", " + TIME.format(start) + " to " + DAY.format(end) + ", " + TIME.format(end);
    }

    /** What a time is called: a part of a day, a day, a week... */
    public static String name(SummaryType type, boolean partial) {
        return switch (type) {
            case ROLLING -> "an earlier part of the conversation";
            case DAY -> partial ? "the conversation so far" : "the day";
            case WEEK -> "the week";
            case MONTH -> "the month";
            case YEAR -> "the year";
        };
    }

    /** The summaries, oldest first, each with the time it covers on a line before it. */
    public String list(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append('[').append(label(entry)).append("]\n").append(entry.text().strip());
        }
        return out.toString();
    }

    private String label(Entry entry) {
        String kind = switch (entry.type()) {
            case WEEK -> "Week";
            case MONTH -> "Month";
            case YEAR -> "Year";
            default -> entry.partial() ? "Part of a day" : "Day";
        };
        return kind + ": " + period(entry.type(), entry.partial(), entry.from(), entry.to());
    }
}
