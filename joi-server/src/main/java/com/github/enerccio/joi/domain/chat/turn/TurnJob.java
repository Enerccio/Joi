package com.github.enerccio.joi.domain.chat.turn;

import com.github.enerccio.joi.domain.jobs.JoiJobs;
import com.github.enerccio.marginalia.domain.service.job.JobCallType;
import com.github.enerccio.marginalia.domain.service.job.JobStatus;
import com.github.enerccio.marginalia.domain.service.job.Jobs;

/**
 * The job of one chat turn (see {@link JoiJobs}). A turn is a chain of steps on different threads: the job is opened on the thread
 * of the first step (so it belongs to the user of that thread's request scope) and the other threads enter it, so every request of
 * the turn to a model, and her speech, is a call of it. It ends with the turn.
 */
public final class TurnJob {

    /** What a thread leaves when the step on it is over. */
    public interface Left extends AutoCloseable {
        @Override
        void close();
    }

    private final String label;
    private Jobs.Handle handle;

    public TurnJob(String label) {
        this.label = label;
    }

    /** Makes the turn's job the one of this thread, until the result is closed. */
    public synchronized Left enter() {
        if (handle == null) {
            Jobs.Handle opened = Jobs.openAsync(JoiJobs.CHAT, label, JobCallType.LLM);
            handle = opened;
            return opened::close;
        }
        Jobs.Scope scope = handle.enter();
        return scope::close;
    }

    /** Ends the job. A turn that never ran a step has none. */
    public synchronized void finish(JobStatus status) {
        if (handle != null) {
            handle.finish(status);
        }
    }
}
