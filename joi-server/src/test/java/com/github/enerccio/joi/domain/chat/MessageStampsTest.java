package com.github.enerccio.joi.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** When a message gets the line of its time, and how the pause before it is said. */
class MessageStampsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Prague");

    private static Date at(String time) {
        return Date.from(ZonedDateTime.of(2026, 11, 20, Integer.parseInt(time.substring(0, 2)), Integer.parseInt(time.substring(3, 5)), 0, 0, ZONE).toInstant());
    }

    @Test
    void aMessageGetsALineWhenItIsTheFirstAfterAPauseOrOnAnotherDay() {
        assertThat(MessageStamps.due(at("10:00"), null, ZONE)).isTrue();
        assertThat(MessageStamps.due(at("10:09"), at("10:00"), ZONE)).isFalse();
        assertThat(MessageStamps.due(at("10:10"), at("10:00"), ZONE)).isTrue();
        // a few minutes, but another day
        Date lateNight = Date.from(ZonedDateTime.of(2026, 11, 20, 23, 58, 0, 0, ZONE).toInstant());
        Date after = Date.from(ZonedDateTime.of(2026, 11, 21, 0, 2, 0, 0, ZONE).toInstant());
        assertThat(MessageStamps.due(after, lateNight, ZONE)).isTrue();
    }

    @Test
    void itDependsOnlyOnTheMinuteTheMessagesWereSent() {
        // a fraction of a second more or less does not change a message's line from one prompt to the next
        Date before = new Date(at("10:00").getTime() + 41);
        Date sent = new Date(at("10:10").getTime() + 912);
        assertThat(MessageStamps.due(sent, before, ZONE)).isTrue();
        assertThat(MessageStamps.gap(sent, before)).isEqualTo("10 minutes");
        assertThat(MessageStamps.gap(new Date(sent.getTime() - 912), new Date(before.getTime() - 41))).isEqualTo("10 minutes");
    }

    @Test
    void thePauseIsSaidInWords() {
        assertThat(MessageStamps.gap(at("10:00"), null)).isNull();
        assertThat(MessageStamps.gap(at("10:00"), at("10:00"))).isEqualTo("less than a minute");
        assertThat(MessageStamps.gap(at("10:01"), at("10:00"))).isEqualTo("1 minute");
        assertThat(MessageStamps.gap(at("11:00"), at("10:00"))).isEqualTo("1 hour");
        assertThat(MessageStamps.gap(at("14:30"), at("10:00"))).isEqualTo("4 hours");
        Date threeDaysLater = Date.from(at("10:00").toInstant().plusSeconds(3 * 24 * 3600));
        assertThat(MessageStamps.gap(threeDaysLater, at("10:00"))).isEqualTo("3 days");
    }
}
