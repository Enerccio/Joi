package com.github.enerccio.joi.domain.summary;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/** The queue of the summaries: the limit of a lane, one job at a time for an assistant, days before weeks before months before years. */
class SummaryQueueTest {

    /** Holds what the queue starts, so a test decides when a job runs and ends. */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> started = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            started.add(command);
        }

        /** Runs the job that started first. */
        void finishFirst() {
            started.removeFirst().run();
        }
    }

    private static class TestJob implements SummaryQueue.Job {
        final Long joi;
        final int rank;
        final int priority;
        final String key;
        final List<String> log;

        TestJob(List<String> log, long joi, int rank, int priority, String key) {
            this.log = log;
            this.joi = joi;
            this.rank = rank;
            this.priority = priority;
            this.key = key;
        }

        @Override
        public Long joiId() {
            return joi;
        }

        @Override
        public int rank() {
            return rank;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public void run() {
            log.add(joi + ":" + key);
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final SummaryQueue queue = new SummaryQueue(executor);
    private final List<String> log = new ArrayList<>();

    private TestJob job(long joi, int rank, String key) {
        return new TestJob(log, joi, rank, 0, key);
    }

    @Test
    void aLaneRunsAsManyJobsAsItsLimitSaysAndNoMore() {
        for (long joi = 1; joi <= 4; joi++) {
            queue.submit("gpu", 2, job(joi, 0, "a"));
        }
        assertThat(executor.started).hasSize(2);
        assertThat(queue.running("gpu")).isEqualTo(2);
        executor.finishFirst();
        assertThat(executor.started).hasSize(2);
        executor.finishFirst();
        executor.finishFirst();
        executor.finishFirst();
        assertThat(log).containsExactly("1:a", "2:a", "3:a", "4:a");
        assertThat(queue.running("gpu")).isZero();
    }

    @Test
    void lanesAreIndependent() {
        queue.submit("gpu", 1, job(1, 0, "a"));
        queue.submit("api", 1, job(2, 0, "a"));
        assertThat(executor.started).hasSize(2);
    }

    @Test
    void theJobsOfOneAssistantNeverRunSideBySide() {
        // two free places, but both jobs are hers
        queue.submit("gpu", 2, job(1, 0, "first"));
        queue.submit("gpu", 2, job(1, 0, "second"));
        assertThat(executor.started).hasSize(1);
        assertThat(queue.pending(1L)).hasSize(1);
        executor.finishFirst();
        assertThat(executor.started).hasSize(1);
        executor.finishFirst();
        assertThat(log).containsExactly("1:first", "1:second");
    }

    @Test
    void daysComeBeforeWeeksBeforeMonthsBeforeYears() {
        queue.submit("gpu", 1, job(9, 0, "other"));
        queue.submit("gpu", 1, job(1, 3, "year"));
        queue.submit("gpu", 1, job(1, 2, "month"));
        queue.submit("gpu", 1, job(1, 1, "week"));
        queue.submit("gpu", 1, job(1, 0, "day"));
        assertThat(queue.pending(1L)).extracting(SummaryQueue.Job::key).containsExactly("day", "week", "month", "year");
        for (int i = 0; i < 5; i++) {
            executor.finishFirst();
        }
        assertThat(log).containsExactly("9:other", "1:day", "1:week", "1:month", "1:year");
    }

    @Test
    void withinARankTheLowerPriorityGoesFirst() {
        queue.submit("gpu", 1, job(9, 0, "other"));
        queue.submit("gpu", 1, new TestJob(log, 1, 0, 1, "quiet"));
        queue.submit("gpu", 1, new TestJob(log, 1, 0, 0, "roll"));
        for (int i = 0; i < 3; i++) {
            executor.finishFirst();
        }
        assertThat(log).containsExactly("9:other", "1:roll", "1:quiet");
    }

    @Test
    void anAssistantThatCameFirstIsServedFirst() {
        queue.submit("gpu", 1, job(9, 0, "busy"));
        queue.submit("gpu", 1, job(2, 0, "a"));
        queue.submit("gpu", 1, job(3, 0, "a"));
        for (int i = 0; i < 3; i++) {
            executor.finishFirst();
        }
        assertThat(log).containsExactly("9:busy", "2:a", "3:a");
    }

    @Test
    void theSameJobIsQueuedOnce() {
        assertThat(queue.submit("gpu", 1, job(1, 0, "a"))).isTrue();
        // it runs now, and asked for again it is not queued
        assertThat(queue.submit("gpu", 1, job(1, 0, "a"))).isFalse();
        assertThat(queue.submit("gpu", 1, job(1, 0, "b"))).isTrue();
        assertThat(queue.submit("gpu", 1, job(1, 0, "b"))).isFalse();
        assertThat(queue.pending(1L)).hasSize(1);
        assertThat(queue.running(1L)).hasSize(1);
    }

    @Test
    void aJobThatFailsGivesItsPlaceBack() {
        queue.submit("gpu", 1, new TestJob(log, 1, 0, 0, "fails") {
            @Override
            public void run() {
                throw new IllegalStateException("no model");
            }
        });
        queue.submit("gpu", 1, job(2, 0, "next"));
        executor.finishFirst();
        executor.finishFirst();
        assertThat(log).containsExactly("2:next");
    }

    @Test
    void theLimitThatWasAskedForLastHolds() {
        queue.submit("gpu", 1, job(1, 0, "a"));
        queue.submit("gpu", 3, job(2, 0, "a"));
        queue.submit("gpu", 3, job(3, 0, "a"));
        assertThat(executor.started).hasSize(3);
    }

    @Test
    void watchersAreToldWhenAJobChanges() {
        List<Long> told = new ArrayList<>();
        queue.onChange(told::add);
        queue.submit("gpu", 1, job(5, 0, "a"));
        assertThat(told).isNotEmpty().containsOnly(5L);
    }
}
