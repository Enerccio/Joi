package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.PartSwitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What the user changes in her parts, held back and sent together. A change is <em>staged</em>: for the part only the
 * last state counts, and nothing is sent until the user was quiet for {@code quietMillis}, so that several changes
 * (the visor off, the gloves on) reach the server in one go, and she can react to all of them in one turn. Then the
 * batch is <em>sent</em>, a frame with an id for each part, and further changes are refused ({@link #change} returns
 * false, the controls are locked) until the server has answered every one of them ({@link #kept} or {@link #failed}),
 * or {@code timeoutMillis} passed. Only then {@code resolved} is told what the server kept, and that is what is shown
 * on her body.
 * <p>
 * Thread-safe; the callbacks run on the staging thread, the thread that answered or the one that cancelled: the owner
 * moves them to its own.
 */
public final class PartStaging {

    /** Sends one part of a batch; false when the node is not connected (the batch is dropped). */
    public interface Sender {
        boolean send(String id, String part, String state);
    }

    /** The batch is over: what the server kept, and what it did not (refused, no answer, or not connected). */
    public interface Resolved {
        void resolved(List<PartSwitch> kept, List<PartSwitch> failed);
    }

    private final long quietMillis;
    private final long timeoutMillis;
    private final ScheduledExecutorService timer;
    private final Sender sender;
    private final Consumer<Boolean> locked;
    private final Resolved resolved;

    /** Staged: the part → the state, in the order the parts were first changed. */
    private final Map<String, String> staged = new LinkedHashMap<>();
    /** Sent and not answered: the frame's id → what it asked for. */
    private final Map<String, PartSwitch> inFlight = new LinkedHashMap<>();
    private final List<PartSwitch> kept = new ArrayList<>();
    private final List<PartSwitch> failed = new ArrayList<>();
    private ScheduledFuture<?> sendTimer;
    private ScheduledFuture<?> timeoutTimer;
    private long counter;
    private boolean sending;

    /**
     * @param quietMillis   how long the user has to be quiet before what was staged is sent
     * @param timeoutMillis how long the server has to answer a batch
     * @param locked        told {@code true} when a batch is sent (changes are refused), {@code false} when it is resolved
     */
    public PartStaging(long quietMillis, long timeoutMillis, ScheduledExecutorService timer, Sender sender,
                       Consumer<Boolean> locked, Resolved resolved) {
        this.quietMillis = quietMillis;
        this.timeoutMillis = timeoutMillis;
        this.timer = timer;
        this.sender = sender;
        this.locked = locked;
        this.resolved = resolved;
    }

    /**
     * The user put a part in a state. Staged, and sent after a quiet moment.
     *
     * @return false when a batch is waiting for the server: nothing was staged, the control shows what is kept
     */
    public synchronized boolean change(String part, String state) {
        if (sending) {
            return false;
        }
        staged.remove(part);
        staged.put(part, state);
        if (sendTimer != null) {
            sendTimer.cancel(false);
        }
        sendTimer = timer.schedule(this::send, quietMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Whether a batch waits for the server. */
    public synchronized boolean locked() {
        return sending;
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
        List<PartSwitch> batch;
        synchronized (this) {
            if (sending || staged.isEmpty()) {
                return;
            }
            if (sendTimer != null) {
                sendTimer.cancel(false);
                sendTimer = null;
            }
            batch = new ArrayList<>();
            staged.forEach((part, state) -> batch.add(new PartSwitch(part, state)));
            staged.clear();
            sending = true;
            kept.clear();
            failed.clear();
        }
        locked.accept(true);
        List<PartSwitch> unsent = new ArrayList<>();
        synchronized (this) {
            for (PartSwitch change : batch) {
                String id = "p-" + (++counter);
                inFlight.put(id, change);
                if (!sender.send(id, change.part(), change.state())) {
                    inFlight.remove(id);
                    unsent.add(change);
                }
            }
            failed.addAll(unsent);
            if (!inFlight.isEmpty()) {
                timeoutTimer = timer.schedule(this::timedOut, timeoutMillis, TimeUnit.MILLISECONDS);
            }
        }
        finishIfDone();
    }

    /** The server kept the frame's part. Unknown ids (not a part of this node's) are ignored; returns whether it was one. */
    public boolean kept(String id) {
        synchronized (this) {
            PartSwitch change = inFlight.remove(id);
            if (change == null) {
                return false;
            }
            kept.add(change);
        }
        finishIfDone();
        return true;
    }

    /** The server refused the frame's part (or it can't be said). Returns whether it was one of this node's. */
    public boolean failed(String id) {
        synchronized (this) {
            PartSwitch change = inFlight.remove(id);
            if (change == null) {
                return false;
            }
            failed.add(change);
        }
        finishIfDone();
        return true;
    }

    /**
     * The connection was lost: what waits for an answer is lost, and what is staged is dropped; both are told as not
     * kept, so the controls show what is kept again.
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
            if (sending) {
                failed.addAll(inFlight.values());
                inFlight.clear();
            }
        }
        if (!dropped.isEmpty()) {
            resolved.resolved(List.of(), dropped);
        }
        finishIfDone();
    }

    private void timedOut() {
        synchronized (this) {
            failed.addAll(inFlight.values());
            inFlight.clear();
        }
        finishIfDone();
    }

    private void finishIfDone() {
        List<PartSwitch> keptNow;
        List<PartSwitch> failedNow;
        synchronized (this) {
            if (!sending || !inFlight.isEmpty()) {
                return;
            }
            if (timeoutTimer != null) {
                timeoutTimer.cancel(false);
                timeoutTimer = null;
            }
            keptNow = List.copyOf(kept);
            failedNow = List.copyOf(failed);
            kept.clear();
            failed.clear();
            sending = false;
        }
        locked.accept(false);
        resolved.resolved(keptNow, failedNow);
    }
}
