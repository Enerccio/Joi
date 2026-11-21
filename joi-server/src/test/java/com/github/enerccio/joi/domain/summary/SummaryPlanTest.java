package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.collections.SummaryType;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Cover;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Job;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Kind;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Settings;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Unit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What is due when: the pause, the growing conversation, the day that ended, the week, month and year that ended. */
class SummaryPlanTest {

    private static final LocalTime FIVE = LocalTime.of(5, 0);
    private final SummaryDays days = new SummaryDays(LocalTime.of(4, 0), ZoneOffset.UTC);
    private final Settings settings = new Settings(45, false, 1000, FIVE, FIVE, FIVE);

    private static Instant at(String text) {
        return Instant.parse(text);
    }

    private static Unit unit(String when) {
        return new Unit(at(when), 10, false);
    }

    private static Cover day(String from, boolean partial) {
        Instant start = at(from);
        return new Cover(SummaryType.DAY, start, partial ? start.plusSeconds(1800) : start.plusSeconds(86400), partial);
    }

    private static Cover period(SummaryType type, String from, String to) {
        return new Cover(type, at(from), at(to), false);
    }

    private List<Job> plan(String now, Settings s, List<Cover> covers, List<Unit> units, boolean waiting) {
        return SummaryPlan.plan(at(now), s, days, covers, units, waiting);
    }

    private static List<Kind> kinds(List<Job> jobs) {
        return jobs.stream().map(Job::kind).toList();
    }

    // ------------------------------------------------------------------------------------------ a pause

    @Test
    void theConversationIsSummarizedAfterAPause() {
        List<Unit> units = List.of(unit("2026-11-20T10:00:00Z"), unit("2026-11-20T10:10:00Z"));
        assertThat(kinds(plan("2026-11-20T10:50:00Z", settings, List.of(), units, false))).isEmpty();
        assertThat(kinds(plan("2026-11-20T10:55:00Z", settings, List.of(), units, false))).containsExactly(Kind.QUIET);
    }

    @Test
    void aPauseOfZeroTurnsItOff() {
        Settings off = new Settings(0, false, 1000, FIVE, FIVE, FIVE);
        assertThat(plan("2026-11-20T20:00:00Z", off, List.of(), List.of(unit("2026-11-20T10:00:00Z")), false)).isEmpty();
    }

    @Test
    void aTaskThatIsOpenHoldsTheSummaryBack() {
        List<Unit> units = List.of(unit("2026-11-20T10:00:00Z"));
        assertThat(plan("2026-11-20T12:00:00Z", settings, List.of(), units, true)).isEmpty();
        assertThat(kinds(plan("2026-11-20T12:00:00Z", settings, List.of(), units, false))).containsExactly(Kind.QUIET);
    }

    @Test
    void whatASummaryCoversIsNotSummarizedAgain() {
        Cover part = new Cover(SummaryType.DAY, at("2026-11-20T09:00:00Z"), at("2026-11-20T10:00:00Z").plusMillis(1), true);
        List<Unit> units = List.of(unit("2026-11-20T09:30:00Z"), unit("2026-11-20T10:00:00Z"));
        assertThat(plan("2026-11-20T12:00:00Z", settings, List.of(part), units, false)).isEmpty();
        assertThat(kinds(plan("2026-11-20T12:00:00Z", settings, List.of(part), List.of(unit("2026-11-20T11:00:00Z")), false)))
                .containsExactly(Kind.QUIET);
    }

    @Test
    void theAnswersToSummariesNeverMakeASummaryOfTheirOwn() {
        List<Unit> noise = List.of(new Unit(at("2026-11-20T10:00:00Z"), 10, true), new Unit(at("2026-11-20T10:01:00Z"), 10, true));
        assertThat(plan("2026-11-20T20:00:00Z", settings, List.of(), noise, false)).isEmpty();
        // ...unless something else was said with them
        List<Unit> mixed = List.of(new Unit(at("2026-11-20T10:00:00Z"), 10, true), unit("2026-11-20T10:01:00Z"));
        assertThat(kinds(plan("2026-11-20T20:00:00Z", settings, List.of(), mixed, false))).containsExactly(Kind.QUIET);
    }

    // ------------------------------------------------------------------------------------------ a growing conversation

    @Test
    void theOldestMessagesAreSummarizedWhenTheConversationGrows() {
        Settings auto = new Settings(45, true, 1000, FIVE, FIVE, FIVE);
        List<Unit> units = List.of(new Unit(at("2026-11-20T10:00:00Z"), 600, false), new Unit(at("2026-11-20T10:01:00Z"), 600, false));
        assertThat(kinds(plan("2026-11-20T10:02:00Z", auto, List.of(), units, false))).containsExactly(Kind.AUTO);
        // off, or not too long yet
        assertThat(plan("2026-11-20T10:02:00Z", settings, List.of(), units, false)).isEmpty();
        assertThat(plan("2026-11-20T10:02:00Z", auto, List.of(), units.subList(0, 1), false)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ a day that ended

    @Test
    void aDayThatEndedIsRolledUp() {
        List<Cover> covers = List.of(day("2026-11-20T10:00:00Z", true), day("2026-11-20T15:00:00Z", true));
        assertThat(plan("2026-11-21T03:00:00Z", settings, covers, List.of(), false)).isEmpty();
        List<Job> jobs = plan("2026-11-21T04:30:00Z", settings, covers, List.of(), false);
        assertThat(jobs).containsExactly(new Job(Kind.DAY_ROLL, at("2026-11-20T04:00:00Z"), at("2026-11-21T04:00:00Z")));
    }

    @Test
    void aDayThatIsRolledUpIsLeftAlone() {
        List<Cover> covers = List.of(day("2026-11-20T04:00:00Z", false));
        assertThat(plan("2026-11-21T06:00:00Z", settings, covers, List.of(), false)).isEmpty();
    }

    @Test
    void messagesLeftOverFromADayThatEndedAreRolledIn() {
        List<Unit> units = List.of(unit("2026-11-20T22:00:00Z"));
        List<Job> jobs = plan("2026-11-21T05:00:00Z", settings, List.of(), units, false);
        assertThat(kinds(jobs)).contains(Kind.DAY_ROLL);
    }

    // ------------------------------------------------------------------------------------------ weeks, months, years

    @Test
    void theWeekThatEndedIsSummarizedAndTheOneGoingOnIsNot() {
        // 2026-11-20 is a Friday: the week of the 9th ended on the 16th at 4:00
        List<Cover> covers = List.of(day("2026-11-10T04:00:00Z", false), day("2026-11-17T04:00:00Z", false));
        List<Job> jobs = plan("2026-11-18T10:00:00Z", settings, covers, List.of(), false);
        assertThat(jobs).containsExactly(new Job(Kind.WEEK, at("2026-11-09T04:00:00Z"), at("2026-11-16T04:00:00Z")));
    }

    @Test
    void aDaySummaryOfTheTimeAfterItAlwaysStays() {
        // nothing newer than the week: it waits, so there is always a day summary
        List<Cover> covers = List.of(day("2026-11-10T04:00:00Z", false));
        assertThat(plan("2026-11-18T10:00:00Z", settings, covers, List.of(), false)).isEmpty();
        // a part of the new week's day is enough
        List<Cover> withNewDay = List.of(day("2026-11-10T04:00:00Z", false), day("2026-11-16T10:00:00Z", true));
        assertThat(kinds(plan("2026-11-18T10:00:00Z", settings, withNewDay, List.of(), false))).contains(Kind.WEEK);
    }

    @Test
    void theWeekWaitsForTheTimeOfDayTheUserChose() {
        Settings late = new Settings(45, false, 1000, LocalTime.of(9, 0), FIVE, FIVE);
        List<Cover> covers = List.of(day("2026-11-10T04:00:00Z", false), day("2026-11-16T04:10:00Z", true));
        assertThat(plan("2026-11-16T08:00:00Z", late, covers, List.of(), false)).isEmpty();
        assertThat(kinds(plan("2026-11-16T09:00:00Z", late, covers, List.of(), false))).containsExactly(Kind.WEEK);
    }

    @Test
    void aWeekThatIsSummarizedIsNotSummarizedAgain() {
        List<Cover> covers = List.of(day("2026-11-10T04:00:00Z", false), day("2026-11-17T04:00:00Z", false),
                period(SummaryType.WEEK, "2026-11-09T04:00:00Z", "2026-11-16T04:00:00Z"));
        assertThat(plan("2026-11-18T10:00:00Z", settings, covers, List.of(), false)).isEmpty();
    }

    @Test
    void aWeekWaitsForTheDaysInsideItToBeRolledUp() {
        List<Cover> covers = List.of(day("2026-11-10T04:00:00Z", false), day("2026-11-12T10:00:00Z", true), day("2026-11-17T04:00:00Z", false));
        List<Job> jobs = plan("2026-11-18T10:00:00Z", settings, covers, List.of(), false);
        assertThat(kinds(jobs)).containsExactly(Kind.DAY_ROLL);
    }

    @Test
    void aMonthWaitsForItsWeeksAndThenCoversThem() {
        List<Cover> covers = new java.util.ArrayList<>(List.of(day("2026-10-06T04:00:00Z", false), day("2026-11-03T04:00:00Z", false)));
        List<Job> jobs = plan("2026-11-04T10:00:00Z", settings, covers, List.of(), false);
        // the week of the 5th (October) comes first; the month of October is looked at when it is done
        assertThat(kinds(jobs)).containsExactly(Kind.WEEK);
        covers.add(period(SummaryType.WEEK, "2026-10-05T04:00:00Z", "2026-10-12T04:00:00Z"));
        jobs = plan("2026-11-04T10:00:00Z", settings, covers, List.of(), false);
        assertThat(jobs).containsExactly(new Job(Kind.MONTH, at("2026-10-01T04:00:00Z"), at("2026-11-01T04:00:00Z")));
    }

    @Test
    void aYearIsSummarizedAfterItsMonths() {
        List<Cover> covers = List.of(period(SummaryType.MONTH, "2026-03-01T04:00:00Z", "2026-04-01T04:00:00Z"),
                day("2027-01-02T04:00:00Z", false));
        List<Job> jobs = plan("2027-01-03T10:00:00Z", settings, covers, List.of(), false);
        assertThat(jobs).contains(new Job(Kind.YEAR, at("2026-01-01T04:00:00Z"), at("2027-01-01T04:00:00Z")));
    }

    @Test
    void aLongerSummaryThatExistsMakesTheShorterOneUseless() {
        List<Cover> covers = List.of(day("2026-10-06T04:00:00Z", false), day("2026-11-03T04:00:00Z", false),
                period(SummaryType.MONTH, "2026-10-01T04:00:00Z", "2026-11-01T04:00:00Z"));
        assertThat(plan("2026-11-04T10:00:00Z", settings, covers, List.of(), false)).isEmpty();
    }

    @Test
    void nothingToSummarizeNothingIsMade() {
        assertThat(plan("2026-11-20T10:00:00Z", settings, List.of(), List.of(), false)).isEmpty();
    }

    @Test
    void theJobsComeInTheOrderTheyRun() {
        assertThat(Kind.DAY_ROLL.rank()).isLessThan(Kind.WEEK.rank());
        assertThat(Kind.WEEK.rank()).isLessThan(Kind.MONTH.rank());
        assertThat(Kind.MONTH.rank()).isLessThan(Kind.YEAR.rank());
        assertThat(Kind.DAY_ROLL.priority()).isLessThan(Kind.QUIET.priority());
    }

    @Test
    void theSameJobHasTheSameKey() {
        assertThat(new Job(Kind.QUIET, null, null).key()).isEqualTo(new Job(Kind.QUIET, null, null).key());
        assertThat(new Job(Kind.MANUAL, null, at("2026-11-20T10:00:00Z")).key()).isNotEqualTo(new Job(Kind.MANUAL, null, at("2026-11-20T11:00:00Z")).key());
    }
}
