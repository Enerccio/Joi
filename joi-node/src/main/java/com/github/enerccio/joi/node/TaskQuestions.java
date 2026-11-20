package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.TaskInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tasks of hers that wait for the user's answer, as the server's {@code task} frames say them (since 1.7): the ones
 * the user was not asked ahead of time ("approve all of this tool" is the server's). A client keeps this and shows the
 * oldest. A frame of the same id with another status takes the question back (it was answered, here or elsewhere).
 */
public final class TaskQuestions {

    private final Map<String, TaskInfo> waiting = new LinkedHashMap<>();

    /**
     * Takes a {@code task} frame.
     *
     * @return the task when it is a new question, null when it is not (a change of one that does not wait, a task that
     *         waits again)
     */
    public synchronized TaskInfo take(TaskInfo task) {
        if ("waiting".equals(task.status())) {
            return waiting.putIfAbsent(task.id(), task) == null ? task : null;
        }
        waiting.remove(task.id());
        return null;
    }

    /** The question asked first and not answered, null for none. */
    public synchronized TaskInfo oldest() {
        return waiting.isEmpty() ? null : waiting.values().iterator().next();
    }

    /** The question is answered (by this client); it is also taken back by the frame that follows. */
    public synchronized void answered(String id) {
        waiting.remove(id);
    }

    public synchronized List<TaskInfo> all() {
        return new ArrayList<>(waiting.values());
    }
}
