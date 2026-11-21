package com.github.enerccio.joi.domain.jobs;

import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.marginalia.domain.service.job.Jobs;

/**
 * Names the work of Joi that talks to an external AI (or runs a heavy program), so that the administrator's log of jobs and the page
 * {@code /jobs} show what it is ({@link Jobs}). Every request to a model is a call of the job of its thread already (the platform
 * records it); these jobs group the calls of one piece of work: a chat turn, a summary, a task of hers, the description of a model, a
 * conversion. Only metadata, never the text.
 * <p>
 * Open a job on a thread that works for the user (inside the request scope of the work): the job's owner is that user.
 */
public final class JoiJobs {

    /** A chat turn: her reply with its tool rounds and her speech. */
    public static final String CHAT = "chat";
    /** A summary job, see {@code SummarizerService}. */
    public static final String SUMMARY = "summary";
    /** A task of hers that reads the web, see {@code WebTaskService}. */
    public static final String WEB_TASK = "web-task";
    /** How a model or an outfit looks, described by a multimodal model. */
    public static final String APPEARANCE = "appearance";
    /** A conversion of a model or a clip with Blender. */
    public static final String CONVERSION = "conversion";

    private JoiJobs() {
    }

    /** The work of a job. */
    @FunctionalInterface
    public interface Work<T, E extends Throwable> {
        T call() throws E;
    }

    /** The work of a job that has no result. */
    @FunctionalInterface
    public interface Task<E extends Throwable> {
        void run() throws E;
    }

    /**
     * Runs the work as a job on this thread: it ends as completed, or as failed when the work throws.
     *
     * @param type what the calls of the job that name no type of their own are
     */
    public static <T, E extends Throwable> T call(String kind, String label, JobCallType type, Work<T, E> work) throws E {
        try (Jobs.Handle job = Jobs.open(kind, label, type)) {
            try {
                return work.call();
            } catch (Throwable t) {
                job.fail();
                throw t;
            }
        }
    }

    /** Like {@link #call}, for work with no result. */
    public static <E extends Throwable> void run(String kind, String label, JobCallType type, Task<E> work) throws E {
        call(kind, label, type, () -> {
            work.run();
            return null;
        });
    }
}
