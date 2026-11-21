package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.summary.SummaryDays.Span;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Her day, week, month and year: they begin at the day boundary, not at midnight. */
class SummaryDaysTest {

    private final SummaryDays days = new SummaryDays(LocalTime.of(4, 0), ZoneOffset.UTC);

    private static Instant at(String text) {
        return Instant.parse(text);
    }

    @Test
    void aLateNightStillBelongsToTheDayBefore() {
        assertThat(days.dateOf(at("2026-11-21T03:59:00Z"))).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(days.dateOf(at("2026-11-21T04:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 21));
        Span day = days.spanOf(SummaryType.DAY, at("2026-11-21T01:00:00Z"));
        assertThat(day.from()).isEqualTo(at("2026-11-20T04:00:00Z"));
        assertThat(day.to()).isEqualTo(at("2026-11-21T04:00:00Z"));
    }

    @Test
    void aWeekRunsFromMonday() {
        // 2026-11-20 is a Friday
        Span week = days.spanOf(SummaryType.WEEK, at("2026-11-20T10:00:00Z"));
        assertThat(week.from()).isEqualTo(at("2026-11-16T04:00:00Z"));
        assertThat(week.to()).isEqualTo(at("2026-11-23T04:00:00Z"));
        // the small hours of Monday are still Sunday's, so the week before
        assertThat(days.spanOf(SummaryType.WEEK, at("2026-11-16T02:00:00Z")).from()).isEqualTo(at("2026-11-09T04:00:00Z"));
        assertThat(days.lastDate(week)).isEqualTo(LocalDate.of(2026, 11, 22));
    }

    @Test
    void aMonthAndAYear() {
        Span month = days.spanOf(SummaryType.MONTH, at("2026-12-31T20:00:00Z"));
        assertThat(month.from()).isEqualTo(at("2026-12-01T04:00:00Z"));
        assertThat(month.to()).isEqualTo(at("2027-01-01T04:00:00Z"));
        Span year = days.spanOf(SummaryType.YEAR, at("2027-01-01T02:00:00Z"));
        assertThat(year.from()).isEqualTo(at("2026-01-01T04:00:00Z"));
        assertThat(days.next(year).from()).isEqualTo(at("2027-01-01T04:00:00Z"));
    }

    @Test
    void theSummaryIsMadeAtTheTimeOfDayButNeverBeforeThePeriodEnded() {
        Instant end = at("2026-11-16T04:00:00Z");
        assertThat(days.runAt(end, LocalTime.of(5, 30))).isEqualTo(at("2026-11-16T05:30:00Z"));
        assertThat(days.runAt(end, LocalTime.of(3, 0))).isEqualTo(end);
    }

    @Test
    void periodsContainWhatLiesInsideThem() {
        Span week = days.spanOf(SummaryType.WEEK, at("2026-11-20T10:00:00Z"));
        assertThat(week.contains(at("2026-11-17T04:00:00Z"), at("2026-11-18T04:00:00Z"))).isTrue();
        assertThat(week.contains(at("2026-11-22T04:00:00Z"), at("2026-11-24T04:00:00Z"))).isFalse();
    }
}
