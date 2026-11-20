package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.MessageStampTemplateData;
import com.github.enerccio.marginalia.utils.AppClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * The line before a message of the user or an event in her prompt: when it was said (and, after a pause, how long after
 * the message before it), from {@code JoiPromptField.MESSAGE_STAMP}. It is put in the prompt only, never stored with
 * the message, and it depends only on the message and the one before it, never on the clock of the prompt: the
 * message reads the same when it is the newest and in every prompt after, so the model's cache of the prompt is not
 * broken by it. Her own replies have none.
 */
public final class MessageStamps {

    /** A message gets its line when this long (or a day change) passed since the one before. */
    public static final Duration PAUSE = Duration.ofMinutes(10);

    private MessageStamps() {
    }

    /**
     * Whether the message gets a line: the first one, after a pause, or on another day (the day of the server's zone).
     *
     * @param previous when the message before it was sent, null when there is none
     */
    public static boolean due(Date sent, Date previous, ZoneId zone) {
        if (previous == null) {
            return true;
        }
        Instant now = minute(sent);
        Instant before = minute(previous);
        return Duration.between(before, now).compareTo(PAUSE) >= 0
                || !now.atZone(zone).toLocalDate().equals(before.atZone(zone).toLocalDate());
    }

    /** How long after the one before, in words ({@code 3 hours}); null when there is none. */
    public static String gap(Date sent, Date previous) {
        if (previous == null) {
            return null;
        }
        Duration since = Duration.between(minute(previous), minute(sent));
        long minutes = Math.max(0, since.toMinutes());
        if (minutes < 1) {
            return "less than a minute";
        }
        if (minutes < 60) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        long hours = since.toHours();
        if (hours < 48) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        return since.toDays() + " days";
    }

    /**
     * The text of the message as it goes into the prompt: the line of its time, then the text.
     *
     * @param force the line is there whatever the rule says (the first message of the prompt: she must know the day)
     */
    public static String stamped(PersonaService persona, Joi joi, String text, Date sent, Date previous, boolean force) throws Exception {
        ZoneId zone = AppClock.INSTANCE.getZone();
        if (sent == null || !force && !due(sent, previous, zone)) {
            return text;
        }
        MessageStampTemplateData data = new MessageStampTemplateData();
        data.setGap(gap(sent, previous));
        // {{weekday}}, {{date}} and {{time}} are the moment the message was sent
        data.templateContext().setClock(Clock.fixed(sent.toInstant().truncatedTo(ChronoUnit.MINUTES), zone));
        String line = persona.render(joi, JoiPromptField.MESSAGE_STAMP, data);
        return line.isBlank() ? text : line + "\n" + text;
    }

    private static Instant minute(Date date) {
        return date.toInstant().truncatedTo(ChronoUnit.MINUTES);
    }
}
