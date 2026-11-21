package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.collections.SummaryType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * The times a summary covers. Her day runs from the day boundary to the next one (4:00 to 4:00: a late night counts as
 * the day before), a week from Monday's boundary, a month from the first day's, a year from January 1st's. Pure: it
 * knows only the boundary and the zone.
 */
public final class SummaryDays {

    /** A time a summary covers: {@code from} (inclusive) to {@code to} (exclusive). */
    public record Span(SummaryType type, Instant from, Instant to) {

        /** Whether the other span lies inside this one. */
        public boolean contains(Instant otherFrom, Instant otherTo) {
            return !otherFrom.isBefore(from) && !otherTo.isAfter(to);
        }
    }

    private final LocalTime boundary;
    private final ZoneId zone;

    public SummaryDays(LocalTime boundary, ZoneId zone) {
        this.boundary = boundary;
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The date of her day the moment belongs to (03:00 on the 21st is still the 20th with a boundary of 4:00). */
    public LocalDate dateOf(Instant moment) {
        LocalDateTime local = LocalDateTime.ofInstant(moment, zone);
        return local.minusHours(boundary.getHour()).minusMinutes(boundary.getMinute()).toLocalDate();
    }

    /** When the day of the date began. */
    public Instant start(LocalDate date) {
        return date.atTime(boundary).atZone(zone).toInstant();
    }

    /** The span of the type the moment belongs to. */
    public Span spanOf(SummaryType type, Instant moment) {
        LocalDate date = dateOf(moment);
        LocalDate first = switch (type) {
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
            default -> date;
        };
        LocalDate next = switch (type) {
            case WEEK -> first.plusWeeks(1);
            case MONTH -> first.plusMonths(1);
            case YEAR -> first.plusYears(1);
            default -> first.plusDays(1);
        };
        return new Span(type, start(first), start(next));
    }

    /** The span right after it. */
    public Span next(Span span) {
        return spanOf(span.type(), span.to());
    }

    /** The last date a span covers (its end is the start of the day after). */
    public LocalDate lastDate(Span span) {
        return dateOf(span.to().minusMillis(1));
    }

    /**
     * The earliest moment the summary of a span that ended may be made: the time of day on the date the span ended, but
     * never before it ended.
     */
    public Instant runAt(Instant spanEnd, LocalTime time) {
        Instant at = LocalDate.ofInstant(spanEnd, zone).atTime(time).atZone(zone).toInstant();
        return at.isAfter(spanEnd) ? at : spanEnd;
    }
}
