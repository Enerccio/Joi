package com.github.enerccio.joi.node.voice;

import java.io.IOException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The user talks to her: push-to-talk. {@link #start} opens the microphone and listens until the user pauses, ends it
 * ({@link #finish}) or gives up ({@link #cancel}); then the microphone is let go, the speech is turned into text and the
 * listener is told what was said. One recording at a time. It runs on a thread of its own, so the listener is called from
 * there (the app moves to its own thread before it touches the screen).
 *
 * <p>The models are loaded when they are first needed and kept ({@link #preload} does it earlier, so that the first
 * recording does not wait for them).
 */
public final class VoiceInput implements AutoCloseable {

    /** What happens, in order: {@code listening}, {@code transcribing}, then {@code heard}, {@code nothing} or {@code failed}. */
    public interface Listener {
        /** The microphone is open: the user can talk. */
        void listening();

        /** The user is done; the words are being worked out. */
        void transcribing();

        /** What the user said. */
        void heard(String text);

        /** Nothing to say: nobody talked, the words were not recognized, or the user gave up. */
        void nothing(String why);

        /** It did not work. */
        void failed(String message);
    }

    /** A recording of the microphone. */
    @FunctionalInterface
    public interface Inputs {
        AudioInput open() throws IOException;
    }

    public enum State {
        IDLE, LISTENING, TRANSCRIBING
    }

    /** How long the user has to begin to talk, and the longest they can go on. */
    static final int WAIT_MS = 8_000;
    static final int MAX_MS = 60_000;

    private final Inputs inputs;
    private final Supplier<SpeechGate> gates;
    private final Supplier<Transcriber> transcribers;
    private final IntSupplier pauseMs;
    private final int waitMs;
    private final int maxMs;
    private SpeechGate gate;
    private Transcriber transcriber;
    private volatile State state = State.IDLE;
    private volatile boolean finishing;
    private volatile boolean cancelled;

    /**
     * The voice input of this machine: the microphone and the sherpa-onnx models of the node's home.
     *
     * @param pauseMs the pause that ends what the user says, read at the start of each recording (the setting)
     */
    public static VoiceInput of(VoiceModels models, SpeechModel model, IntSupplier pauseMs) {
        return new VoiceInput(Microphone::open, () -> new SileroGate(models.vad()),
                () -> new SherpaTranscriber(models.dir(model)), pauseMs, WAIT_MS, MAX_MS);
    }

    VoiceInput(Inputs inputs, Supplier<SpeechGate> gates, Supplier<Transcriber> transcribers, IntSupplier pauseMs,
               int waitMs, int maxMs) {
        this.inputs = inputs;
        this.gates = gates;
        this.transcribers = transcribers;
        this.pauseMs = pauseMs;
        this.waitMs = waitMs;
        this.maxMs = maxMs;
    }

    public State state() {
        return state;
    }

    /**
     * Starts a recording.
     *
     * @return false when one is already running (nothing is started then)
     */
    public synchronized boolean start(Listener listener) {
        if (state != State.IDLE) {
            return false;
        }
        state = State.LISTENING;
        finishing = false;
        cancelled = false;
        Thread.ofPlatform().name("joi-voice").daemon().start(() -> run(listener));
        return true;
    }

    /** The user is done talking (they let the key go, or pressed the button again): what was said so far is used. */
    public void finish() {
        finishing = true;
    }

    /** The user gave up: nothing is used. */
    public void cancel() {
        cancelled = true;
    }

    /** Loads the models now, on this thread (the first recording is not slowed by it); a failure is left to the first recording. */
    public void preload() {
        try {
            load();
        } catch (RuntimeException | LinkageError e) {
            // the recording that needs it tells the user
        }
    }

    private synchronized void load() {
        if (gate == null) {
            gate = gates.get();
        }
        if (transcriber == null) {
            transcriber = transcribers.get();
        }
    }

    private void run(Listener listener) {
        String failure = null;
        String nothing = null;
        String heard = null;
        try {
            try {
                load();
            } catch (RuntimeException | LinkageError e) {
                failure = "The speech model could not be loaded: " + e.getMessage();
                return;
            }
            gate.reset();
            UtteranceDetector.Utterance utterance;
            try (AudioInput input = inputs.open()) {
                UtteranceDetector detector = new UtteranceDetector(gate, pauseMs.getAsInt(), waitMs, maxMs);
                listener.listening();
                utterance = record(input, detector);
            } catch (IOException e) {
                failure = e.getMessage();
                return;
            }
            // the microphone is let go: what was said is turned into text
            if (cancelled) {
                nothing = "Cancelled.";
            } else if (!utterance.spoken()) {
                nothing = "I heard nothing.";
            } else {
                state = State.TRANSCRIBING;
                listener.transcribing();
                String said = transcriber.transcribe(utterance.samples());
                String text = said == null ? "" : said.strip();
                if (text.isEmpty()) {
                    nothing = "I could not make out any words.";
                } else {
                    heard = text;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            failure = "Speech to text failed: " + e.getMessage();
        } finally {
            state = State.IDLE;
            if (failure != null) {
                listener.failed(failure);
            } else if (nothing != null) {
                listener.nothing(nothing);
            } else if (heard != null) {
                listener.heard(heard);
            }
        }
    }

    /** Reads frames until the detector says it is over, or the user finishes or cancels. */
    private UtteranceDetector.Utterance record(AudioInput input, UtteranceDetector detector) throws IOException {
        while (true) {
            if (cancelled) {
                return detector.finish();
            }
            if (finishing) {
                return detector.finish();
            }
            float[] frame = input.read();
            if (frame == null) {
                return detector.finish();
            }
            UtteranceDetector.Utterance utterance = detector.accept(frame);
            if (utterance != null) {
                return utterance;
            }
        }
    }

    /** Lets the models go: the node is closing. */
    @Override
    public synchronized void close() {
        cancelled = true;
        if (gate != null) {
            gate.close();
            gate = null;
        }
        if (transcriber != null) {
            transcriber.close();
            transcriber = null;
        }
    }
}
