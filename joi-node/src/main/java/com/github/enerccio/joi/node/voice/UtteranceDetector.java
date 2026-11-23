package com.github.enerccio.joi.node.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the end of what the user says. It is fed the recording frame by frame ({@link SpeechGate#FRAME} samples each) and
 * says when the utterance is over: after a pause (the user stopped talking), when the user ended it themselves
 * ({@link #finish}), when nothing was said for too long, or when it got too long. What it gives back is the speech with a
 * little before and after it, without the silence around.
 *
 * <p>A noise that is over before it is long enough to be a word (a click, a cough) is forgotten, and the detector goes on
 * waiting. One detector is for one utterance.
 */
public final class UtteranceDetector {

    /** Why the utterance is over. */
    public enum End {
        /** The user paused for as long as the setting says. */
        PAUSE,
        /** The user ended it (the button or key). */
        FINISHED,
        /** Nobody said anything: the samples are empty. */
        NO_SPEECH,
        /** It went on for the longest time an utterance may. */
        TOO_LONG
    }

    /**
     * What was said.
     *
     * @param samples the speech, 16 kHz mono, -1 to 1 (empty for {@link End#NO_SPEECH})
     * @param end     why it is over
     */
    public record Utterance(float[] samples, End end) {
        /** Whether there is speech in it. */
        public boolean spoken() {
            return samples.length > 0;
        }
    }

    /** A frame is speech from this probability. */
    static final float THRESHOLD = 0.5f;
    /** Speech shorter than this many frames (about 130 ms) is a noise. */
    static final int MIN_SPEECH_FRAMES = 4;
    /** Kept before the first speech and after the last (the start of a word and its fade), in frames. */
    static final int PRE_ROLL_FRAMES = 8;
    static final int TAIL_FRAMES = 6;

    private final SpeechGate gate;
    private final int pauseFrames;
    private final int waitFrames;
    private final int maxFrames;
    private final List<float[]> frames = new ArrayList<>();
    private int first = -1;
    private int last = -1;
    private int speechFrames;
    private int silentFrames;
    private boolean over;

    /**
     * @param gate     what tells speech from silence
     * @param pauseMs  the pause that ends an utterance
     * @param waitMs   how long to wait for the user to say anything
     * @param maxMs    the longest an utterance can be
     */
    public UtteranceDetector(SpeechGate gate, int pauseMs, int waitMs, int maxMs) {
        this.gate = gate;
        this.pauseFrames = Math.max(1, frames(pauseMs));
        this.waitFrames = Math.max(1, frames(waitMs));
        this.maxFrames = Math.max(this.waitFrames, frames(maxMs));
    }

    private static int frames(int ms) {
        return (int) Math.ceil(ms * (double) SpeechGate.SAMPLE_RATE / 1000 / SpeechGate.FRAME);
    }

    /**
     * The next frame of the recording.
     *
     * @return the utterance when this frame ends it, null while it goes on
     */
    public Utterance accept(float[] frame) {
        if (over) {
            throw new IllegalStateException("The utterance is over");
        }
        frames.add(frame);
        int index = frames.size() - 1;
        if (gate.probability(frame) >= THRESHOLD) {
            if (first < 0) {
                first = index;
            }
            last = index;
            speechFrames++;
            silentFrames = 0;
        } else if (first >= 0) {
            silentFrames++;
        }
        if (first >= 0 && silentFrames >= pauseFrames) {
            if (speechFrames >= MIN_SPEECH_FRAMES) {
                return end(End.PAUSE);
            }
            // a noise, not a word: forgotten, and the detector goes on waiting
            first = -1;
            last = -1;
            speechFrames = 0;
            silentFrames = 0;
        }
        if (frames.size() >= maxFrames) {
            return end(spoken() ? End.TOO_LONG : End.NO_SPEECH);
        }
        if (first < 0 && frames.size() >= waitFrames) {
            return end(End.NO_SPEECH);
        }
        return null;
    }

    /** The user ended it: what was said so far, or nothing. */
    public Utterance finish() {
        return end(spoken() ? End.FINISHED : End.NO_SPEECH);
    }

    /** Whether enough speech was heard to be a word. */
    private boolean spoken() {
        return first >= 0 && speechFrames >= MIN_SPEECH_FRAMES;
    }

    private Utterance end(End how) {
        over = true;
        if (how == End.NO_SPEECH) {
            return new Utterance(new float[0], how);
        }
        int from = Math.max(0, first - PRE_ROLL_FRAMES);
        int to = Math.min(frames.size(), last + 1 + TAIL_FRAMES);
        float[] samples = new float[(to - from) * SpeechGate.FRAME];
        for (int i = from; i < to; i++) {
            System.arraycopy(frames.get(i), 0, samples, (i - from) * SpeechGate.FRAME, SpeechGate.FRAME);
        }
        return new Utterance(samples, how);
    }
}
