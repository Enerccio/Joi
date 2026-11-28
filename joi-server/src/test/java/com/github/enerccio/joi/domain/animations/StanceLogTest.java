package com.github.enerccio.joi.domain.animations;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger of her recent stances: the last first, at most five, said with their times and reasons. */
class StanceLogTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void theLastComesFirstAndTheOldestGo() {
        StanceLog log = new StanceLog(List.of());
        for (int i = 0; i < 7; i++) {
            log = log.with(new StanceLog.Entry("a" + i, at(28, 10, i), at(28, 10, i + 1), null, null));
        }
        assertThat(log.entries()).hasSize(StanceLog.SIZE).first().extracting(StanceLog.Entry::animation).isEqualTo("a6");
        assertThat(log.entries().getLast().animation()).isEqualTo("a2");
        assertThat(StanceLog.fromJson(log.toJson())).isEqualTo(log);
        assertThat(StanceLog.fromJson("not json").entries()).isEmpty();
        assertThat(StanceLog.fromJson(null).entries()).isEmpty();
    }

    @Test
    void itIsSaidWithItsTimesAndReasonsAndAGoneAnimationIsLeftOut() {
        StanceLog log = new StanceLog(List.of())
                .with(new StanceLog.Entry("gone", at(27, 9, 0), at(27, 9, 5), null, null))
                .with(new StanceLog.Entry("sit", at(27, 23, 50), at(28, 0, 10), null, null))
                .with(new StanceLog.Entry("dance", at(28, 11, 58), at(28, 12, 5), "Peter asked  me\nto dance", "he said stop"));
        Map<String, String> names = Map.of("dance", "Dance", "sit", "Sitting");
        assertThat(log.describe(names::get, ZONE)).isEqualTo("\"Dance\" (Saturday 11:58 AM to 12:05 PM; taken because: Peter asked me "
                + "to dance; ended because: he said stop), \"Sitting\" (Friday 11:50 PM to Saturday 12:10 AM)");
        assertThat(new StanceLog(List.of()).describe(names::get, ZONE)).isNull();
        assertThat(StanceLog.oneLine("x".repeat(300))).hasSize(StanceLog.REASON_LENGTH);
    }
}
