package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.PartSwitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What the user changes in her parts, held back and sent together, and what is shown only when the server says so.
 * <ol>
 *   <li><b>Staged.</b> A change is held: for a part only the last state counts, and nothing is sent until the user was
 *   quiet for {@link Timing#quietMillis}, so that several changes (the visor off, the gloves on) reach the server in
 *   one go, and she can react to all of them in one turn.</li>
 *   <li><b>Sent.</b> A frame with an id for each part. Further changes are refused ({@link #change} returns false, the
 *   controls are locked) until the server has answered every one: {@link #kept}, {@link #refused} (her personality
 *   lets her decide, and she did not allow it) or {@link #failed}. When it does not answer in {@link Timing#answerMillis}
 *   the node asks what the server has ({@link Status}): a part in the state asked for is kept, one not (yet) in it
 *   waits for another {@code answerMillis}, up to {@link Timing#checks} times; when the server can't be reached it is
 *   gone and nothing was kept.</li>
 *   <li><b>Waiting for her reaction</b> (only when the node shows the changes with it): kept and her reaction is to come
 *   ({@code told}), the controls stay locked until {@link #reaction} (her turn began, or ended), or
 *   {@link Timing#reactionMillis} passed.</li>
 * </ol>
 * Then {@code resolved} is told what the server kept, refused, and what is not kept: that is what is shown on her body.
 * <p>
 * Thread-safe; the callbacks run on the staging thread, the thread that answered or the one that cancelled: the owner
 * moves them to its own.
 *
 * @see PartSwitch
 */
public final class PartStaging {

    /**
     * The waits.
     *
     * @param quietMillis    how long the user has to be quiet before what was staged is sent
     * @param answerMillis   how long the server has to answer a batch before the node asks what it has
     * @param checks         how many times the node asks before it gives up on a part the server does not have yet
     * @param reactionMillis how long the node waits for her reaction before it shows the change anyway
     */
    public record Timing(long quietMillis, long answerMillis, int checks, long reactionMillis) {
    }

    /** Sends one part of a batch; false when the node is not connected (the batch is dropped). */
    public interface Sender {
        boolean send(String id, String part, String state);
    }

    /** What the server has: the state of each part (the part's id → the state's id). Throws when it can't be reached. */
    public interface Status {
        Map<String, String> states() throws Exception;
    }

    /** A change she did not allow, and what she said about it (null for nothing). */
    public record Refusal(PartSwitch change, String reason) {
    }

    /** The batch is over: what the server kept, what it did not (no answer, refused by an error, not connected), what she refused. */
    public interface Resolved {
        void resolved(List<PartSwitch> kept, List<PartSwitch> failed, List<Refusal> refused);
    }

    private record Kept(PartSwitch change, boolean told) {
    }

    private enum Phase {
        IDLE, ANSWERS, REACTION
    }

    private final Timing timing;
    private final ScheduledExecutorService timer;
    private final Sender sender;
    private final Status status;
    private final BooleanSupplier waitsForReaction;
    private final Consumer<Boolean> locked;
    private final Resolved resolved;

    /** Staged: the part → the state, in the order the parts were first changed. */
    private final Map<String, String> staged = new LinkedHashMap<>();
    /** Sent and not answered: the frame's id → what it asked for. */
    private final Map<String, PartSwitch> inFlight = new LinkedHashMap<>();
    private final List<Kept> kept = new ArrayList<>();
    private final List<PartSwitch> failed = new ArrayList<>();
    private final List<Refusal> refused = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private ScheduledFuture<?> sendTimer;
    private ScheduledFuture<?> answerTimer;
    private ScheduledFuture<?> reactionTimer;
    private long counter;
    /** Each batch is a generation: what a late check or timer says of an older one is ignored. */
    private long generation;
    private int checked;
    /** Asked as a batch is about to be sent: whether the user's words go with it. */
    private volatile BooleanSupplier withWords = () -> false;
    /** Told, after a batch the user's words go with is sent, how many of its parts went out. */
    private volatile java.util.function.IntConsumer afterSent = sent -> {
    };

    /**
     * @param waitsForReaction whether a change is shown with her reaction (asked when a batch is answered)
     * @param locked           told {@code true} when a batch is sent (changes are refused), {@code false} when it is resolved
     */
    public PartStaging(Timing timing, ScheduledExecutorService timer, Sender sender, Status status, BooleanSupplier waitsForReaction,
                       Consumer<Boolean> locked, Resolved resolved) {
        this.timing = timing;
        this.timer = timer;
        this.sender = sender;
        this.status = status;
        this.waitsForReaction = waitsForReaction;
        this.locked = locked;
        this.resolved = resolved;
    }

    /**
     * The user put a part in a state. Staged, and sent after a quiet moment.
     *
     * @return false when a batch waits (for the server, or for her reaction): nothing was staged, the control shows what is kept
     */
    public synchronized boolean change(String part, String state) {
        if (phase != Phase.IDLE) {
            return false;
        }
        staged.remove(part);
        staged.put(part, state);
        if (sendTimer != null) {
            sendTimer.cancel(false);
        }
        sendTimer = timer.schedule(this::send, timing.quietMillis(), TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * The user's words that go with a batch of parts. {@code ask} is asked when a batch is about to be sent (the controls
     * are locked then; it may take as long as the user does) and says whether the words go with it; if they do,
     * {@code sent} is told after the parts went out, with how many (none when the node was not connected), so that the
     * words follow right after and the server tells her both in one turn. Both are called on the staging thread.
     */
    public void withWords(BooleanSupplier ask, java.util.function.IntConsumer sent) {
        this.withWords = ask;
        this.afterSent = sent;
    }

    /** Whether a batch waits (for the server, or for her reaction). */
    public synchronized boolean locked() {
        return phase != Phase.IDLE;
    }

    /** Whether something is staged, not sent yet. */
    public synchronized boolean pending() {
        return !staged.isEmpty();
    }

    /** Sends what is staged now, without waiting for the quiet moment (the node is closing). */
    public void flush() {
        send();
    }

    private void send() {
        List<PartSwitch> batch = new ArrayList<>();
        long mine;
        synchronized (this) {
            if (phase != Phase.IDLE || staged.isEmpty()) {
                return;
            }
            if (sendTimer != null) {
                sendTimer.cancel(false);
                sendTimer = null;
            }
            staged.forEach((part, state) -> batch.add(new PartSwitch(part, state)));
            staged.clear();
            phase = Phase.ANSWERS;
            mine = ++generation;
            checked = 0;
            kept.clear();
            failed.clear();
            refused.clear();
        }
        locked.accept(true);
        boolean words = withWords.getAsBoolean();
        synchronized (this) {
            for (PartSwitch change : batch) {
                String id = "p-" + (++counter);
                inFlight.put(id, change);
                if (!sender.send(id, change.part(), change.state())) {
                    inFlight.remove(id);
                    failed.add(change);
                }
            }
            if (!inFlight.isEmpty()) {
                long gen = mine;
                answerTimer = timer.schedule(() -> check(gen), timing.answerMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (words) {
            afterSent.accept(batch.size() - sentFailed(batch));
        }
        answered();
    }

    private synchronized int sentFailed(List<PartSwitch> batch) {
        return (int) failed.stream().filter(batch::contains).count();
    }

    /** The server kept the frame's part. Returns whether it was one of the batch waiting (a late answer is not). */
    public boolean kept(String id, boolean told) {
        synchronized (this) {
            PartSwitch change = inFlight.remove(id);
            if (change == null) {
                return false;
            }
            kept.add(new Kept(change, told));
        }
        answered();
        return true;
    }

    /** She did not allow the frame's part. Returns whether it was one of the batch waiting. */
    public boolean refused(String id, String reason) {
        synchronized (this) {
            PartSwitch change = inFlight.remove(id);
            if (change == null) {
                return false;
            }
            refused.add(new Refusal(change, reason));
        }
        answered();
        return true;
    }

    /** The server refused the frame's part (an error). Returns whether it was one of the batch waiting. */
    public boolean failed(String id) {
        synchronized (this) {
            PartSwitch change = inFlight.remove(id);
            if (change == null) {
                return false;
            }
            failed.add(change);
        }
        answered();
        return true;
    }

    /** Her reaction to what was kept began (or is over): the batch that waited for it is shown. */
    public void reaction() {
        synchronized (this) {
            if (phase != Phase.REACTION) {
                return;
            }
        }
        resolve();
    }

    /**
     * The connection was lost: what waits for an answer is lost, what is staged is dropped (both are told as not kept,
     * so the controls show what is kept again), and what the server kept is shown without waiting for her reaction.
     */
    public void cancel() {
        List<PartSwitch> dropped = new ArrayList<>();
        synchronized (this) {
            if (sendTimer != null) {
                sendTimer.cancel(false);
                sendTimer = null;
            }
            staged.forEach((part, state) -> dropped.add(new PartSwitch(part, state)));
            staged.clear();
            if (phase == Phase.ANSWERS) {
                failed.addAll(inFlight.values());
                inFlight.clear();
            }
        }
        if (!dropped.isEmpty()) {
            resolved.resolved(List.of(), dropped, List.of());
        }
        resolve();
    }

    /** The server did not answer in time: asks what it has. */
    private void check(long gen) {
        Map<String, PartSwitch> waiting;
        synchronized (this) {
            if (gen != generation || phase != Phase.ANSWERS) {
                return;
            }
            checked++;
            waiting = new LinkedHashMap<>(inFlight);
        }
        // a request over the network: not on the timer's thread
        Thread.ofVirtual().name("Joi parts status").start(() -> {
            Map<String, String> states;
            try {
                states = status.states();
            } catch (Exception e) {
                states = null;
            }
            synchronized (this) {
                if (gen != generation || phase != Phase.ANSWERS) {
                    return;
                }
                for (Map.Entry<String, PartSwitch> entry : waiting.entrySet()) {
                    PartSwitch change = entry.getValue();
                    if (inFlight.remove(entry.getKey()) == null) {
                        continue;
                    }
                    if (states == null) {
                        // the server is gone
                        failed.add(change);
                    } else if (change.state().equals(states.get(change.part()))) {
                        kept.add(new Kept(change, false));
                    } else if (checked >= timing.checks()) {
                        failed.add(change);
                    } else {
                        // it may still be deciding: asked again later
                        inFlight.put(entry.getKey(), change);
                    }
                }
                if (!inFlight.isEmpty()) {
                    answerTimer = timer.schedule(() -> check(gen), timing.answerMillis(), TimeUnit.MILLISECONDS);
                }
            }
            answered();
        });
    }

    /** Every part of the batch is answered: shown now, or when her reaction comes. */
    private void answered() {
        synchronized (this) {
            if (phase != Phase.ANSWERS || !inFlight.isEmpty()) {
                return;
            }
            if (answerTimer != null) {
                answerTimer.cancel(false);
                answerTimer = null;
            }
            if (kept.stream().anyMatch(Kept::told) && waitsForReaction.getAsBoolean()) {
                phase = Phase.REACTION;
                long gen = generation;
                reactionTimer = timer.schedule(() -> {
                    synchronized (this) {
                        if (gen != generation) {
                            return;
                        }
                    }
                    reaction();
                }, timing.reactionMillis(), TimeUnit.MILLISECONDS);
                return;
            }
        }
        resolve();
    }

    private void resolve() {
        List<PartSwitch> keptNow;
        List<PartSwitch> failedNow;
        List<Refusal> refusedNow;
        synchronized (this) {
            if (phase == Phase.IDLE) {
                return;
            }
            if (answerTimer != null) {
                answerTimer.cancel(false);
                answerTimer = null;
            }
            if (reactionTimer != null) {
                reactionTimer.cancel(false);
                reactionTimer = null;
            }
            keptNow = kept.stream().map(Kept::change).toList();
            failedNow = List.copyOf(failed);
            refusedNow = List.copyOf(refused);
            kept.clear();
            failed.clear();
            refused.clear();
            phase = Phase.IDLE;
        }
        locked.accept(false);
        resolved.resolved(keptNow, failedNow, refusedNow);
    }
}
