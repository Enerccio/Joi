package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AudioChunk;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.JoiAnimations;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * What her body does as her turns come and go, with the animations chosen in her dialog ({@link JoiAnimations}): idle
 * while she waits (one of the idle ones, another each time), talking while she speaks, and her very first waking up
 * (cause {@code first_boot}): she sleeps while the server writes and renders her words, which are held back; when the
 * turn is done she wakes up, and her words come once the waking-up animation is over. A missing animation is skipped
 * (her rest pose). The renderer and the speech are the {@link Avatar}'s; it tells back when they end.
 * <p>
 * Not thread safe: call it from one thread (the UI's, or the socket's in the terminal client).
 */
public class AvatarDirector {

    /** What she is doing. */
    public enum State {
        IDLE, SLEEPING, WAKING_UP, TALKING, FALLING_ASLEEP
    }

    /** The renderer and the speaker. */
    public interface Avatar {
        /** Plays one of the animations of her body's {@code .joia} (its id), null for her rest pose. */
        void play(String animation, boolean loop);

        /** A piece of her reply as the model writes it. */
        default void text(String turn, String delta) {
        }

        /** Her reply is whole. */
        default void reply(String turn, ChatReply reply) {
        }

        /** A piece of her speech to play after the ones before; tell {@link #speechEnded} when the last one played. */
        default void speak(String turn, AudioChunk chunk) {
        }

        /** Her turn failed or was stopped. */
        default void failed(String turn, ApiError error) {
        }

        /** She is in another state now. */
        default void state(State state) {
        }
    }

    /** The cause of her very first waking up ({@code TurnStart}). */
    public static final String FIRST_BOOT = "first_boot";

    private final Avatar avatar;
    private final RandomGenerator random;
    private JoiAnimations roles;
    private State state = State.IDLE;
    private String lastIdle;
    private String lastTalking;

    /** The first-boot turn held back while she sleeps, null for none. */
    private String held;
    private final List<Runnable> heldFrames = new ArrayList<>();
    private boolean heldDone;
    /** Her turn now, and whether its speech still plays. */
    private String speakingTurn;
    private boolean speechPlaying;
    private boolean speechExpected;

    public AvatarDirector(Avatar avatar, JoiAnimations roles, RandomGenerator random) {
        this.avatar = avatar;
        this.roles = roles == null ? new JoiAnimations(null, null, null, List.of(), List.of()) : roles;
        this.random = random;
    }

    public State state() {
        return state;
    }

    /** The animations chosen in her dialog changed. */
    public void roles(JoiAnimations roles) {
        this.roles = roles;
    }

    /** She is shown: idle. */
    public void start() {
        idle();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // her turns
    // ----------------------------------------------------------------------------------------------------------------

    public void turnStarted(String turn, String cause, boolean speak) {
        if (FIRST_BOOT.equals(cause) && roles.sleeping() != null) {
            held = turn;
            heldFrames.clear();
            heldDone = false;
            enter(State.SLEEPING, roles.sleeping(), true);
        }
        speechExpected = speak;
    }

    public void text(String turn, String delta) {
        if (hold(turn, () -> text(turn, delta))) {
            return;
        }
        avatar.text(turn, delta);
    }

    public void reply(String turn, ChatReply reply) {
        if (hold(turn, () -> reply(turn, reply))) {
            return;
        }
        avatar.reply(turn, reply);
        talk(turn);
    }

    public void audio(String turn, AudioChunk chunk) {
        if (hold(turn, () -> audio(turn, chunk))) {
            return;
        }
        talk(turn);
        speechPlaying = true;
        avatar.speak(turn, chunk);
    }

    public void done(String turn) {
        if (turn != null && turn.equals(held)) {
            heldDone = true;
            wakeUp();
            return;
        }
        if (turn != null && turn.equals(speakingTurn) && (!speechExpected || !speechPlaying)) {
            // no speech (or none came): back to idle once the reply is shown
            speakingTurn = null;
            idle();
        }
    }

    public void failed(String turn, ApiError error) {
        if (turn != null && turn.equals(held)) {
            // she does not wake up into a failure: the words that came are shown, then she waits
            held = null;
            heldDone = false;
            replayHeld(turn);
        }
        avatar.failed(turn, error);
        if (turn != null && turn.equals(speakingTurn) || state == State.SLEEPING) {
            speakingTurn = null;
            speechPlaying = false;
            idle();
        }
    }

    /** The renderer finished an animation that does not loop. */
    public void animationEnded() {
        if (state == State.WAKING_UP) {
            String turn = held;
            held = null;
            idle();
            replayHeld(turn);
        } else if (state == State.FALLING_ASLEEP) {
            enter(State.SLEEPING, roles.sleeping(), true);
        }
    }

    /** The last piece of her speech was played. */
    public void speechEnded() {
        speechPlaying = false;
        if (state == State.TALKING) {
            speakingTurn = null;
            idle();
        }
    }

    /** She goes to sleep (the user said good night, the node is idle for long). */
    public void fallAsleep() {
        if (roles.fallingAsleep() != null) {
            enter(State.FALLING_ASLEEP, roles.fallingAsleep(), false);
        } else {
            enter(State.SLEEPING, roles.sleeping(), true);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------

    /** A frame of the held turn is kept for after she wakes up; true when it was. */
    private boolean hold(String turn, Runnable frame) {
        if (turn != null && turn.equals(held)) {
            heldFrames.add(frame);
            return true;
        }
        return false;
    }

    private void wakeUp() {
        if (roles.wakingUp() != null) {
            enter(State.WAKING_UP, roles.wakingUp(), false);
        } else {
            String turn = held;
            held = null;
            idle();
            replayHeld(turn);
        }
    }

    /** Her held words now that she is awake, then the end of the turn as it came. */
    private void replayHeld(String turn) {
        List<Runnable> frames = List.copyOf(heldFrames);
        heldFrames.clear();
        frames.forEach(Runnable::run);
        if (heldDone) {
            heldDone = false;
            done(turn);
        }
    }

    private void talk(String turn) {
        speakingTurn = turn;
        if (state != State.TALKING) {
            enter(State.TALKING, pick(roles.talking(), lastTalking), true);
            lastTalking = current;
        }
    }

    private void idle() {
        enter(State.IDLE, pick(roles.idle(), lastIdle), true);
        lastIdle = current;
    }

    private String current;

    private void enter(State next, String animation, boolean loop) {
        state = next;
        current = animation;
        avatar.play(animation, loop);
        avatar.state(next);
    }

    /** One of the list, not the one played last when there is another; null for an empty list. */
    private String pick(List<String> ids, String last) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }
        List<String> others = ids.stream().filter(id -> !id.equals(last)).toList();
        return others.get(random.nextInt(others.size()));
    }
}
