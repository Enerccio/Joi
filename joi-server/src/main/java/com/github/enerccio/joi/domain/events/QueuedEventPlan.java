package com.github.enerccio.joi.domain.events;

import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * What a node that connects is told of the events that waited (see {@code DESIGN.md} → Events, Queued events): the events
 * <b>merged</b>, as the design says of rapid actions ("toggling the halo five times is one event with the final state"),
 * and the ones that no longer matter left out. Pure: it only decides; the caller tells her and marks the events handled.
 * <ul>
 *   <li>too old ({@link #MAX_AGE}), or about a body that is gone: dropped (a task's result is never dropped for its age);</li>
 *   <li>a part switched again and again: one event, from the first state to the last; back where it began, none;</li>
 *   <li>a name, her personality, her voice or her body changed again and again: the same, from the first value to the
 *       last (the texts of the steps in a row when there were several, as they say what happened); back where it began, none;</li>
 *   <li>a body described again, an outfit put on again: the last one;</li>
 *   <li>the rest (a task's result, what is new) as it came.</li>
 * </ul>
 */
public final class QueuedEventPlan {

    /** How long an event is worth telling. */
    public static final Duration MAX_AGE = Duration.ofDays(7);

    /** The kinds that change a value from one to another: the chain of them is from the first {@code from} to the last {@code to}. */
    private static final Set<String> CHAINS = Set.of(JoiEvents.RENAMED, JoiEvents.USER_RENAMED, JoiEvents.PERSONALITY_CHANGED,
            JoiEvents.VOICE_CHANGED, JoiEvents.BODY_SWITCHED, JoiEvents.MOOD_CHANGED);

    /** The kinds that are about one body ({@code payload.to}): when it is gone they do not matter. */
    private static final Set<String> OF_A_BODY = Set.of(JoiEvents.PART_SWITCHED, JoiEvents.BODY_DESCRIBED, JoiEvents.OUTFIT_CHANGED);

    /**
     * One thing to tell her.
     *
     * @param kind    the kind of the last event it is made of
     * @param text    what she is told
     * @param sources the events it is made of, in the order they happened (all of them are handled by telling it)
     */
    public record Told(String kind, String text, List<QueuedEvent> sources) {
    }

    /**
     * @param told    what to tell her, in the order it happened
     * @param dropped the events that are handled without telling her
     */
    public record Result(List<Told> told, List<QueuedEvent> dropped) {
    }

    private QueuedEventPlan() {
    }

    /**
     * @param waiting    the events that wait, in the order they happened
     * @param bodyExists whether a body (its uuid) is still there
     */
    public static Result of(List<QueuedEvent> waiting, Instant now, Predicate<String> bodyExists) {
        List<QueuedEvent> dropped = new ArrayList<>();
        // the groups of events that are one: by what they change, in the order of their first event
        Map<String, List<QueuedEvent>> groups = new LinkedHashMap<>();
        int single = 0;
        for (QueuedEvent event : waiting) {
            JsonObject payload = payload(event);
            boolean old = !JoiEvents.TASK_ENDED.equals(event.getKind()) && event.getOccurredAt() != null
                    && event.getOccurredAt().toInstant().isBefore(now.minus(MAX_AGE));
            String body = OF_A_BODY.contains(event.getKind()) ? string(payload, "to") : null;
            if (old || body != null && !bodyExists.test(body)) {
                dropped.add(event);
                continue;
            }
            String key = switch (event.getKind()) {
                case JoiEvents.PART_SWITCHED -> "part|" + body + "|" + string(payload, "part");
                case JoiEvents.BODY_DESCRIBED -> "described|" + string(payload, "to");
                case JoiEvents.OUTFIT_CHANGED -> "outfit|" + string(payload, "to");
                default -> CHAINS.contains(event.getKind()) ? "chain|" + event.getKind() : "single|" + single++;
            };
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }

        List<Told> told = new ArrayList<>();
        for (List<QueuedEvent> group : groups.values()) {
            QueuedEvent first = group.getFirst();
            QueuedEvent last = group.getLast();
            if (group.size() > 1 && backWhereItBegan(first, last)) {
                dropped.addAll(group);
                continue;
            }
            String text = last.getText();
            if (group.size() > 1 && CHAINS.contains(last.getKind())) {
                text = group.stream().map(QueuedEvent::getText).filter(StringUtils::isNotBlank).collect(Collectors.joining("\n"));
            }
            told.add(new Told(last.getKind(), text, List.copyOf(group)));
        }
        // in the order it happened: by the last event of each
        Map<QueuedEvent, Integer> position = new java.util.IdentityHashMap<>();
        for (QueuedEvent event : waiting) {
            position.put(event, position.size());
        }
        told.sort((a, b) -> Integer.compare(position.get(a.sources().getLast()), position.get(b.sources().getLast())));
        return new Result(told, dropped);
    }

    /** Whether a series of changes of one thing ended in the state it began in: nothing to tell. */
    private static boolean backWhereItBegan(QueuedEvent first, QueuedEvent last) {
        if (JoiEvents.PART_SWITCHED.equals(last.getKind())) {
            return Objects.equals(string(payload(first), "fromState"), string(payload(last), "toState"));
        }
        if (CHAINS.contains(last.getKind())) {
            return Objects.equals(string(payload(first), "from"), string(payload(last), "to"));
        }
        return false;
    }

    private static JsonObject payload(QueuedEvent event) {
        try {
            return StringUtils.isBlank(event.getPayload()) ? new JsonObject() : JsonParser.parseString(event.getPayload()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject payload, String name) {
        return payload.has(name) && payload.get(name).isJsonPrimitive() ? payload.get(name).getAsString() : null;
    }
}
