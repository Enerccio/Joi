package com.github.enerccio.joi.node;

import com.github.enerccio.joi.node.PartStaging.Refusal;
import com.github.enerccio.joi.protocol.node.PartSwitch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class PartStagingTest {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<Boolean> locks = new CopyOnWriteArrayList<>();
    private final List<List<PartSwitch>> kept = new CopyOnWriteArrayList<>();
    private final List<List<PartSwitch>> failed = new CopyOnWriteArrayList<>();
    private final List<List<Refusal>> refused = new CopyOnWriteArrayList<>();
    private volatile boolean connected = true;
    private volatile boolean waitsForReaction = false;
    /** What the server has when asked; null: it can't be reached. */
    private volatile Map<String, String> server = Map.of();
    private volatile int asked;

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
    }

    private PartStaging staging(long quiet, long answer, int checks) {
        return staging(quiet, answer, checks, 5000, () -> waitsForReaction);
    }

    private PartStaging staging(long quiet, long answer, int checks, long reaction, BooleanSupplier waits) {
        return new PartStaging(new PartStaging.Timing(quiet, answer, checks, reaction), timer, (id, part, state) -> {
            if (connected) {
                sent.add(id + ":" + part + "=" + state);
            }
            return connected;
        }, () -> {
            asked++;
            if (server == null) {
                throw new java.io.IOException("gone");
            }
            return server;
        }, waits, locks::add, (k, f, r) -> {
            kept.add(k);
            failed.add(f);
            refused.add(r);
        });
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void whatGoesWithTheBatchIsToldHowManyPartsWentOutAfterThem() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        PartStaging staging = new PartStaging(new PartStaging.Timing(50, 5000, 3, 5000), timer, (id, part, state) -> {
            order.add("part " + part);
            return connected;
        }, () -> Map.of(), () -> false, locks::add, (k, f, r) -> {
        });
        staging.withWords(() -> {
            order.add("asked");
            return true;
        }, count -> order.add("sent " + count));
        staging.change("visor", "hidden");
        staging.change("gloves", "shown");
        await(() -> order.size() == 4);
        assertThat(order).containsExactly("asked", "part visor", "part gloves", "sent 2");
        staging.cancel();

        // not connected: the parts did not go out, so nothing goes with them
        order.clear();
        connected = false;
        staging.change("visor", "shown");
        await(() -> order.size() == 3);
        assertThat(order).containsExactly("asked", "part visor", "sent 0");
    }

    @Test
    void changesInAQuietWindowAreSentTogetherTheLastStateOfAPartCounting() throws Exception {
        PartStaging staging = staging(150, 5000, 3);
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
        PartStaging staging = staging(20, 5000, 3);
        staging.change("visor", "hidden");
        staging.change("gloves", "shown");
        staging.change("cape", "hidden");
        await(() -> sent.size() == 3);

        // refused while it waits, and nothing is staged for later
        assertThat(staging.change("hat", "hidden")).isFalse();
        assertThat(staging.pending()).isFalse();

        assertThat(staging.kept("p-1", false)).isTrue();
        assertThat(kept).isEmpty();
        assertThat(staging.locked()).isTrue();
        assertThat(staging.failed("p-2")).isTrue();
        assertThat(staging.kept("p-9", false)).isFalse();
        assertThat(staging.refused("p-3", "Not now.")).isTrue();

        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("gloves", "shown"));
        assertThat(refused.getFirst()).containsExactly(new Refusal(new PartSwitch("cape", "hidden"), "Not now."));
        assertThat(locks).containsExactly(true, false);
        // free again
        assertThat(staging.change("hat", "hidden")).isTrue();
    }

    @Test
    void whenTheNodeShowsChangesWithHerReactionAKeptOneWaitsForIt() throws Exception {
        waitsForReaction = true;
        PartStaging staging = staging(10, 5000, 3);
        staging.change("visor", "hidden");
        await(() -> sent.size() == 1);

        // kept, and her reaction is to come: still locked, nothing shown
        staging.kept("p-1", true);
        assertThat(kept).isEmpty();
        assertThat(staging.locked()).isTrue();
        assertThat(staging.change("gloves", "shown")).isFalse();

        staging.reaction();
        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(locks).containsExactly(true, false);
        // a reaction nobody waits for is nothing
        staging.reaction();
        assertThat(kept).hasSize(1);
    }

    @Test
    void aChangeNoReactionFollowsIsShownAtOnceEvenWhenWaitingForReactions() throws Exception {
        waitsForReaction = true;
        PartStaging staging = staging(10, 5000, 3);
        staging.change("visor", "hidden");
        await(() -> sent.size() == 1);
        // she decided on it herself, or is not told at all: told is false
        staging.kept("p-1", false);
        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
    }

    @Test
    void aReactionThatDoesNotComeIsNotWaitedForForever() throws Exception {
        waitsForReaction = true;
        PartStaging staging = staging(10, 5000, 3, 150, () -> true);
        staging.change("visor", "hidden");
        await(() -> sent.size() == 1);
        staging.kept("p-1", true);
        assertThat(kept).isEmpty();
        await(() -> !kept.isEmpty());
        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void aBatchTheServerDoesNotAnswerIsCheckedAndGivenUpWhenItDoesNotHaveIt() throws Exception {
        server = Map.of("visor", "shown");
        PartStaging staging = staging(10, 100, 2);
        staging.change("visor", "hidden");
        await(() -> !failed.isEmpty());
        // asked twice (it may still be deciding), then given up: the server is there and does not have it
        assertThat(asked).isEqualTo(2);
        assertThat(kept.getFirst()).isEmpty();
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(locks).containsExactly(true, false);
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void aBatchTheServerHasButDidNotAnswerIsTakenAsKept() throws Exception {
        server = Map.of("visor", "hidden");
        PartStaging staging = staging(10, 100, 3);
        staging.change("visor", "hidden");
        staging.change("gloves", "shown");
        await(() -> !kept.isEmpty());
        // the answer was lost: the server has the visor as asked; it does not have the gloves, and is asked again
        assertThat(asked).isEqualTo(3);
        assertThat(kept.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("gloves", "shown"));
    }

    @Test
    void aServerThatCannotBeReachedIsGoneAndNothingWasKept() throws Exception {
        server = null;
        PartStaging staging = staging(10, 100, 3);
        staging.change("visor", "hidden");
        await(() -> !failed.isEmpty());
        assertThat(asked).isEqualTo(1);
        assertThat(kept.getFirst()).isEmpty();
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void nothingIsSentWhileNotConnectedAndTheChangeIsToldAsNotKept() throws Exception {
        connected = false;
        PartStaging staging = staging(10, 5000, 3);
        staging.change("visor", "hidden");
        await(() -> !failed.isEmpty());
        assertThat(sent).isEmpty();
        assertThat(failed.getFirst()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();
    }

    @Test
    void aLostConnectionDropsWhatWaitsAndWhatIsStagedButShowsWhatWasKept() throws Exception {
        PartStaging staging = staging(20, 5000, 3);
        staging.change("visor", "hidden");
        await(() -> sent.size() == 1);
        staging.cancel();
        assertThat(failed.getLast()).containsExactly(new PartSwitch("visor", "hidden"));
        assertThat(staging.locked()).isFalse();

        // staged, not sent
        int before = failed.size();
        staging.change("gloves", "shown");
        staging.cancel();
        assertThat(failed).hasSize(before + 1);
        assertThat(failed.getLast()).containsExactly(new PartSwitch("gloves", "shown"));
        Thread.sleep(100);
        assertThat(sent).hasSize(1);

        // kept, waiting for her reaction: the connection is lost, it is shown
        waitsForReaction = true;
        staging.change("cape", "hidden");
        await(() -> sent.size() == 2);
        staging.kept("p-2", true);
        assertThat(staging.locked()).isTrue();
        staging.cancel();
        assertThat(kept.getLast()).containsExactly(new PartSwitch("cape", "hidden"));
        assertThat(staging.locked()).isFalse();
    }
}
