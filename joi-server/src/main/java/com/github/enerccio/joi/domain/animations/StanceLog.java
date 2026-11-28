package com.github.enerccio.joi.domain.animations;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The ledger of the stances she held lately, the last first (at most {@link #SIZE}): each the animation's uuid and when it
 * began and ended. Kept on her as JSON ({@code Joi.stanceLog}); her body section lists it, so she knows what she did and
 * that it ended.
 *
 * @param entries the stances, the last first
 */
public record StanceLog(List<Entry> entries) {

    /** How many stances are kept. */
    public static final int SIZE = 5;

    /** How long a reason is kept, in characters. */
    public static final int REASON_LENGTH = 200;

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /**
     * One stance.
     *
     * @param animation the uuid of its animation
     * @param from      when it began (epoch milliseconds), 0 when not known
     * @param to        when it ended (epoch milliseconds)
     * @param why       why she took it, in her words; null when not said
     * @param endedWhy  why it ended, in her words (or that the user ended it); null when not said
     */
    public record Entry(String animation, long from, long to, String why, String endedWhy) {
    }

    public StanceLog {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static StanceLog fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return new StanceLog(List.of());
        }
        try {
            List<Entry> entries = GSON.fromJson(json, new TypeToken<List<Entry>>() { }.getType());
            return new StanceLog(entries == null ? List.of() : entries.stream().filter(e -> e != null && e.animation() != null).toList());
        } catch (RuntimeException e) {
            return new StanceLog(List.of());
        }
    }

    public String toJson() {
        return GSON.toJson(entries);
    }

    /** With a stance that ended put first, the oldest dropped past {@link #SIZE}. */
    public StanceLog with(Entry ended) {
        List<Entry> list = new ArrayList<>();
        list.add(ended);
        list.addAll(entries);
        return new StanceLog(list.subList(0, Math.min(SIZE, list.size())));
    }

    /** A moment as her prompt says it: "Saturday 12:05 PM". */
    public static String when(long millis, ZoneId zone) {
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /**
     * The ledger as her prompt says it: {@code "Dance" (Saturday 11:58 AM to 12:05 PM), ...}, the last first; the names from
     * {@code names} (an animation it gives none for, deleted since, is left out); null when nothing is left.
     */
    public String describe(Function<String, String> names, ZoneId zone) {
        List<String> told = new ArrayList<>();
        for (Entry entry : entries) {
            String name = names.apply(entry.animation());
            if (name == null) {
                continue;
            }
            var to = Instant.ofEpochMilli(entry.to()).atZone(zone);
            String span;
            if (entry.from() <= 0) {
                span = "until " + WHEN.format(to);
            } else {
                var from = Instant.ofEpochMilli(entry.from()).atZone(zone);
                span = WHEN.format(from) + " to " + (from.toLocalDate().equals(to.toLocalDate()) ? TIME.format(to) : WHEN.format(to));
            }
            StringBuilder text = new StringBuilder("\"").append(name).append("\" (").append(span);
            if (StringUtils.isNotBlank(entry.why())) {
                text.append("; taken because: ").append(oneLine(entry.why()));
            }
            if (StringUtils.isNotBlank(entry.endedWhy())) {
                text.append("; ended because: ").append(oneLine(entry.endedWhy()));
            }
            told.add(text.append(")").toString());
        }
        return told.isEmpty() ? null : String.join(", ", told);
    }

    /** A reason as one line, at most {@link #REASON_LENGTH} characters. */
    public static String oneLine(String reason) {
        String line = reason == null ? "" : reason.strip().replaceAll("\\s+", " ");
        return line.length() <= REASON_LENGTH ? line : line.substring(0, REASON_LENGTH - 1) + "…";
    }
}
