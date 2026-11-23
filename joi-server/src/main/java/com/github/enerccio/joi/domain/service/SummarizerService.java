package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.summary.SummaryPlan.Kind;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Writes the summaries of the conversation (see {@code DESIGN.md} → Memory, Compaction). It looks at every assistant
 * about once a minute and queues what is due ({@code SummaryPlan}): the conversation so far after a pause, the oldest
 * messages when too many are unsummarized and she summarizes on her own, a day that ended (its parts become one day
 * summary), a week, a month and a year that ended. The jobs wait in the queue of the model's lane
 * ({@link SummaryService#lane}), so the limits of the model and of its GPU hold, and those of one assistant run in the
 * order days, weeks, months, years. Each summary a model wrote is told to her as an event, so she can react.
 * The user can also ask for the summary of messages they chose.
 */
public interface SummarizerService {

    /** A job of the assistant, queued or running. {@code from} and {@code to} are the time of a day or a period, null for the others. */
    record JobInfo(Kind kind, Date from, Date to, boolean running) {
    }

    /** What is running and what waits for the assistant, in the order it runs. */
    List<JobInfo> jobs(Joi joi);

    /** The time up to which the messages are summarized: no summary covers one sent after it. The beginning of time for none. */
    Date coveredUpTo(Joi joi) throws Exception;

    /** The summaries of the past as her prompt shows them, the oldest first; null when there are none. */
    String prompt(Joi joi) throws Exception;

    /** The time a summary covers, in words, as her prompt says it: a day, a week, a month, a year, or a part of a day with its times. */
    String period(Joi joi, Summary summary) throws Exception;

    /**
     * The messages a summary made by hand up to {@code last} covers: all the messages no summary covers, from the first of
     * them to the last one. There are no gaps: a summary always starts where the one before it ended.
     */
    List<ChatMessage> rangeUpTo(Joi joi, ChatMessage last) throws Exception;

    /**
     * Asks for the summary of {@link #rangeUpTo}. Returns at once; the job waits in the queue, and she is told when it is done.
     *
     * @throws IllegalArgumentException when there is nothing to summarize (the message is summarized already)
     * @throws IllegalStateException    when there is no model to write it
     */
    void summarize(Joi joi, ChatMessage last) throws Exception;

    /** Looks at the assistant now and queues what is due, as the minute tick does. */
    void check(Joi joi);

    /**
     * Queues what is due now ({@link #check}) and completes when no job of the assistant waits or runs any more: what
     * they made is told to her by then. Her greeting waits for it, so she greets the user with it, not in a turn after.
     */
    CompletableFuture<Void> settle(Joi joi);

    /** Calls the watcher whenever the summaries or the jobs of the assistant change. Remove it when its owner goes away. */
    Registration watch(Joi joi, Runnable onChange) throws Exception;
}
