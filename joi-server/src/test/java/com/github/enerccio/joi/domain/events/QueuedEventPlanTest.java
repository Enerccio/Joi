package com.github.enerccio.joi.domain.events;

import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What a node that connects is told of the events that waited: merged, and without what no longer matters. */
class QueuedEventPlanTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static QueuedEvent event(String kind, int minutesAgo, String text, String payload) {
        QueuedEvent event = new QueuedEvent();
        event.setKind(kind);
        event.setOccurredAt(Date.from(NOW.minusSeconds(minutesAgo * 60L)));
        event.setText(text);
        event.setPayload(payload);
        return event;
    }

    private static QueuedEvent part(int minutesAgo, String text, String body, String part, String from, String to) {
        return event(JoiEvents.PART_SWITCHED, minutesAgo, text, "{\"to\":\"" + body + "\",\"part\":\"" + part + "\",\"fromState\":"
                + (from == null ? "null" : "\"" + from + "\"") + ",\"toState\":\"" + to + "\",\"by\":\"user\"}");
    }

    private static QueuedEventPlan.Result plan(List<QueuedEvent> waiting) {
        return QueuedEventPlan.of(waiting, NOW, uuid -> !uuid.equals("gone"));
    }

    @Test
    void aPartSwitchedAgainAndAgainIsOneChangeFromTheFirstStateToTheLast() {
        QueuedEvent a = part(50, "Halo hidden.", "b1", "halo", "shown", "hidden");
        QueuedEvent b = part(40, "Halo shown.", "b1", "halo", "hidden", "shown");
        QueuedEvent c = part(30, "Halo hidden.", "b1", "halo", "shown", "hidden");
        QueuedEvent other = part(35, "Ears folded.", "b1", "ears", "up", "folded");
        QueuedEventPlan.Result result = plan(List.of(a, b, other, c));

        assertThat(result.dropped()).isEmpty();
        assertThat(result.told()).extracting(QueuedEventPlan.Told::text).containsExactly("Ears folded.", "Halo hidden.");
        assertThat(result.told().get(1).sources()).containsExactly(a, b, c);
    }

    @Test
    void aPartBackWhereItBeganIsNothingToTell() {
        QueuedEvent a = part(50, "Halo hidden.", "b1", "halo", "shown", "hidden");
        QueuedEvent b = part(40, "Halo shown.", "b1", "halo", "hidden", "shown");
        QueuedEventPlan.Result result = plan(List.of(a, b));
        assertThat(result.told()).isEmpty();
        assertThat(result.dropped()).containsExactly(a, b);
    }

    @Test
    void aValueChangedAgainAndAgainIsTheStepsInARowAndNothingWhenItIsBackWhereItBegan() {
        QueuedEvent one = event(JoiEvents.RENAMED, 30, "You are now called Mira.", "{\"from\":\"Joi\",\"to\":\"Mira\"}");
        QueuedEvent two = event(JoiEvents.RENAMED, 20, "You are now called Kairo.", "{\"from\":\"Mira\",\"to\":\"Kairo\"}");
        QueuedEventPlan.Result result = plan(List.of(one, two));
        assertThat(result.told()).hasSize(1);
        assertThat(result.told().getFirst().text()).isEqualTo("You are now called Mira.\nYou are now called Kairo.");

        QueuedEvent back = event(JoiEvents.RENAMED, 10, "You are now called Joi.", "{\"from\":\"Kairo\",\"to\":\"Joi\"}");
        QueuedEventPlan.Result same = plan(List.of(one, two, back));
        assertThat(same.told()).isEmpty();
        assertThat(same.dropped()).containsExactly(one, two, back);
    }

    @Test
    void aBodyDescribedAgainAndAnOutfitPutOnAgainAreTheLastOne() {
        QueuedEvent first = event(JoiEvents.BODY_DESCRIBED, 30, "Old look.", "{\"to\":\"b1\"}");
        QueuedEvent second = event(JoiEvents.BODY_DESCRIBED, 20, "New look.", "{\"to\":\"b1\"}");
        QueuedEvent street = event(JoiEvents.OUTFIT_CHANGED, 15, "Street.", "{\"to\":\"b1\",\"outfit\":\"street\"}");
        QueuedEvent armor = event(JoiEvents.OUTFIT_CHANGED, 10, "Armor.", "{\"to\":\"b1\",\"outfit\":\"armor\"}");
        QueuedEventPlan.Result result = plan(List.of(first, second, street, armor));
        assertThat(result.told()).extracting(QueuedEventPlan.Told::text).containsExactly("New look.", "Armor.");
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    void whatIsAboutABodyThatIsGoneOrTooOldIsDroppedButATasksResultIsNotDroppedForItsAge() {
        QueuedEvent gone = part(10, "Halo hidden.", "gone", "halo", "shown", "hidden");
        QueuedEvent old = event(JoiEvents.RENAMED, 60 * 24 * 8, "Old name.", "{\"from\":\"a\",\"to\":\"b\"}");
        QueuedEvent task = event(JoiEvents.TASK_ENDED, 60 * 24 * 8, "Your task is finished.", "{\"task\":\"t1\"}");
        QueuedEvent news = event(JoiEvents.BODY_ADDED, 5, "A new body.", "{\"bodies\":[\"b2\"]}");
        QueuedEventPlan.Result result = plan(List.of(gone, old, task, news));
        assertThat(result.dropped()).containsExactlyInAnyOrder(gone, old);
        assertThat(result.told()).extracting(QueuedEventPlan.Told::kind).containsExactly(JoiEvents.TASK_ENDED, JoiEvents.BODY_ADDED);
    }

    @Test
    void thingsThatAreNotTheSameAreNotMergedAndTheOrderIsTheOrderTheyHappened() {
        QueuedEvent task1 = event(JoiEvents.TASK_ENDED, 50, "First result.", "{\"task\":\"t1\"}");
        QueuedEvent task2 = event(JoiEvents.TASK_ENDED, 40, "Second result.", "{\"task\":\"t2\"}");
        QueuedEvent otherBody = part(30, "Halo on the other body.", "b2", "halo", "shown", "hidden");
        QueuedEvent unknown = event("something.new", 20, "Something.", null);
        QueuedEventPlan.Result result = plan(List.of(task1, task2, otherBody, unknown));
        assertThat(result.told()).extracting(QueuedEventPlan.Told::text)
                .containsExactly("First result.", "Second result.", "Halo on the other body.", "Something.");
        assertThat(Set.copyOf(result.dropped())).isEmpty();
    }
}
