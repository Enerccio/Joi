package com.github.enerccio.joi.domain.collections;

/**
 * Where a {@code JoiTask} is. Stored by name, so the names never change.
 */
public enum JoiTaskStatus {

    /** Waiting to run: a task starts only after the reply that started it is finished. */
    QUEUED,
    RUNNING,
    /** Stopped until the user approves or denies a step of it. */
    WAITING,
    DONE,
    FAILED,
    CANCELLED;

    /** Whether the task is over (it will not run again). */
    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
