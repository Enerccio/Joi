package com.github.enerccio.joi.domain.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * The queue of the summaries, in memory. A summary is a call to a model, and a model has limits, so the jobs wait in
 * a lane: the lane of a GPU when the model takes a lock on one (every model on it counts, see
 * {@code SummaryService.lane}), else the lane of the model for the user. A lane runs as many jobs at once as its limit
 * says, and no more. The jobs of one assistant never run side by side and always in the order of their {@link Job#rank()}:
 * the days, then the weeks, then the months, then the years; within a rank the lower {@link Job#priority()}, then the
 * order they came in. A job that was queued or is running is not queued again.
 * <p>
 * The queue knows nothing of models or databases, so it is tested on its own ({@code SummaryQueueTest}).
 */
public final class SummaryQueue {

    private static final Logger log = LoggerFactory.getLogger(SummaryQueue.class);

    /** A job of an assistant. */
    public interface Job {
        Long joiId();

        /** Which of the days, weeks, months and years it is: the lower runs first. */
        int rank();

        /** Within the rank, the lower runs first. */
        int priority();

        /** Two jobs of one assistant with the same key are one. */
        String key();

        /** The work; it runs on the queue's executor, once. */
        void run() throws Exception;
    }

    private static final class Entry {
        final Job job;
        final long order;

        Entry(Job job, long order) {
            this.job = job;
            this.order = order;
        }
    }

    private static final class Lane {
        int limit = 1;
        final List<Entry> pending = new ArrayList<>();
        final Set<Entry> running = new LinkedHashSet<>();
    }

    private final Executor executor;
    private final Map<String, Lane> lanes = new HashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    public SummaryQueue(Executor executor) {
        this.executor = executor;
    }

    /** Called with the id of the assistant whenever a job of hers is queued, starts or ends. */
    public void onChange(LongConsumer listener) {
        listeners.add(listener);
    }

    /**
     * Queues the job in the lane.
     *
     * @param lane  the name of the lane
     * @param limit how many jobs the lane runs at once (at least one); the latest limit asked for is the one that holds
     * @return false when the same job is queued or running already
     */
    public boolean submit(String lane, int limit, Job job) {
        Lane state;
        synchronized (lanes) {
            state = lanes.computeIfAbsent(lane, name -> new Lane());
        }
        synchronized (state) {
            state.limit = Math.max(1, limit);
            if (has(state, job)) {
                return false;
            }
            state.pending.add(new Entry(job, counter.incrementAndGet()));
        }
        changed(job.joiId());
        dispatch(state);
        return true;
    }

    private static boolean has(Lane lane, Job job) {
        return lane.pending.stream().anyMatch(e -> same(e.job, job)) || lane.running.stream().anyMatch(e -> same(e.job, job));
    }

    private static boolean same(Job a, Job b) {
        return a.joiId().equals(b.joiId()) && a.key().equals(b.key());
    }

    /** The jobs of the assistant that wait, in the order they will run. */
    public List<Job> pending(Long joiId) {
        List<Entry> all = new ArrayList<>();
        for (Lane lane : lanesSnapshot()) {
            synchronized (lane) {
                lane.pending.stream().filter(e -> e.job.joiId().equals(joiId)).forEach(all::add);
            }
        }
        all.sort(java.util.Comparator.<Entry>comparingInt(e -> e.job.rank()).thenComparingInt(e -> e.job.priority()).thenComparingLong(e -> e.order));
        return all.stream().map(e -> e.job).toList();
    }

    /** The jobs of the assistant that run now. */
    public List<Job> running(Long joiId) {
        List<Job> all = new ArrayList<>();
        for (Lane lane : lanesSnapshot()) {
            synchronized (lane) {
                lane.running.stream().filter(e -> e.job.joiId().equals(joiId)).forEach(e -> all.add(e.job));
            }
        }
        return all;
    }

    /** How many jobs run in the lane now. */
    public int running(String lane) {
        Lane state;
        synchronized (lanes) {
            state = lanes.get(lane);
        }
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.running.size();
        }
    }

    private List<Lane> lanesSnapshot() {
        synchronized (lanes) {
            return new ArrayList<>(lanes.values());
        }
    }

    private void dispatch(Lane lane) {
        List<Entry> start = new ArrayList<>();
        synchronized (lane) {
            while (lane.running.size() < lane.limit) {
                Entry next = pick(lane);
                if (next == null) {
                    break;
                }
                lane.pending.remove(next);
                lane.running.add(next);
                start.add(next);
            }
        }
        for (Entry entry : start) {
            changed(entry.job.joiId());
            executor.execute(() -> run(lane, entry));
        }
    }

    /** The job to run next: the first job of the assistant that has the least to wait for, of the assistants that do not run one. */
    private static Entry pick(Lane lane) {
        Set<Long> busy = new HashSet<>();
        lane.running.forEach(e -> busy.add(e.job.joiId()));
        Map<Long, Entry> best = new HashMap<>();
        for (Entry entry : lane.pending) {
            if (busy.contains(entry.job.joiId())) {
                continue;
            }
            best.merge(entry.job.joiId(), entry, (a, b) -> before(a, b) ? a : b);
        }
        Entry chosen = null;
        for (Entry entry : best.values()) {
            if (chosen == null || entry.order < chosen.order) {
                chosen = entry;
            }
        }
        return chosen;
    }

    private static boolean before(Entry a, Entry b) {
        if (a.job.rank() != b.job.rank()) {
            return a.job.rank() < b.job.rank();
        }
        if (a.job.priority() != b.job.priority()) {
            return a.job.priority() < b.job.priority();
        }
        return a.order < b.order;
    }

    private void run(Lane lane, Entry entry) {
        try {
            entry.job.run();
        } catch (Throwable t) {
            log.warn("A summary job of {} failed: {}", entry.job.joiId(), t.getMessage(), t);
        } finally {
            synchronized (lane) {
                lane.running.remove(entry);
            }
            changed(entry.job.joiId());
            dispatch(lane);
        }
    }

    private void changed(Long joiId) {
        for (LongConsumer listener : listeners) {
            try {
                listener.accept(joiId);
            } catch (RuntimeException e) {
                log.warn("A watcher of the summaries failed: {}", e.getMessage());
            }
        }
    }
}
