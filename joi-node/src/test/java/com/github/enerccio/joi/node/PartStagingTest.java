package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.PartSwitch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PartStagingTest {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<Boolean> locks = new CopyOnWriteArrayList<>();
    private final List<List<PartSwitch>> kept = new CopyOnWriteArrayList<>();
    private final List<List<PartSwitch>> failed = new CopyOnWriteArrayList<>();
    private volatile boolean connected = true;

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
    }

    private PartStaging staging(long quiet, long timeout) {
        return new PartStaging(quiet, timeout, timer, (id, part, state) -> {
            if (connected) {
                sent.add(id + ":" + part + "=" + state);
            }
            return connected;
        }, locks::add, (k, f) -> {
            kept.add(k);
            failed.add(f);
        });
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void changesInAQuietWindowAreSentTogetherTheLastStateOfAPartCounting() throws Exception {
        PartStaging staging = staging(150, 5000);
        assertThat(staging.change("visor", "hidden")).isTrue();
        assertThat(staging.change("gloves", "shown")).isTrue();
        assertThat(staging.change("visor", "shown")).isTrue();
        assertThat(sent).isEmpty();
        assertThat(staging.pending()).isTrue();

        await(() -> sent.size() == 2);
        assertThat(sent).containsExactly("p-1:gloves=shown", "p-2:visor=shown");
        assertThat(staging.locked()).isTrue();
        assertThat(locks).containsExactly(true);
    }

    @Test
    void aBatchHoldsTheControlsUntilTheServerAnsweredEveryPart() throws Exception {
        PartStaging staging = staging(20, 5000);
        staging.change("visor", "hidden");
        staging.change("gloves", "shown");
        await(() -> sent.size() == 2);

        // refused while it waits, and nothing is staged for later
        assertThat(staging.change("cape", "hidden")).isFalse();
        assertThat(staging.pending()).isFalse();

        assertThat(staging.kept("p-1")).isTrue();
        assertThat(kept).isEmpty();
        assertThat(staging.locked()).isTrue();
        assertThat(staging.failed("p-2")).isTrue();
        assertThat(staging.kept("p-9")).isFalse();

        assertThat(kept).hasSize(1);
        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("gloves", "shown"));
        assertThat(locks).containsExactly(true, false);
        // free again
        assertThat(staging.change("cape", "hidden")).isTrue();
    }

    @Test
    void aBatchTheServerDoesNotAnswerIsGivenUp() throws Exception {
        PartStaging staging = staging(10, 150);
        staging.change("visor", "hidden");
        await(() -> !failed.isEmpty());
        assertThat(kept.getFirst()).isEmpty();
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(locks).containsExactly(true, false);
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void nothingIsSentWhileNotConnectedAndTheChangeIsToldAsNotKept() throws Exception {
        connected = false;
        PartStaging staging = staging(10, 5000);
        staging.change("visor", "hidden");
        await(() -> !failed.isEmpty());
        assertThat(sent).isEmpty();
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void aLostConnectionDropsWhatWaitsAndWhatIsStaged() throws Exception {
        PartStaging staging = staging(20, 5000);
        staging.change("visor", "hidden");
        await(() -> sent.size() == 1);
        staging.cancel();
        assertThat(failed.getLast()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();

        List<List<PartSwitch>> before = new ArrayList<>(failed);
        staging.change("gloves", "shown");
        staging.cancel();
        assertThat(failed).hasSize(before.size() + 1);
        assertThat(failed.getLast()).containsExactly(new PartSwitch("gloves", "shown"));
        Thread.sleep(100);
        assertThat(sent).hasSize(1);
    }
}
