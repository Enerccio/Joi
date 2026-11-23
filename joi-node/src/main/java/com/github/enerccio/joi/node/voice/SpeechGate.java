package com.github.enerccio.joi.node.voice;

/**
 * Tells speech from silence, one frame at a time: the voice activity detector ({@link SileroGate}, Silero through
 * sherpa-onnx). An interface so that the logic around it ({@link UtteranceDetector}) is tested without a model.
 */
public interface SpeechGate {

    /** The sample rate everything in the voice package uses: 16 kHz, mono. */
    int SAMPLE_RATE = 16000;

    /** Samples in a frame: 32 ms, the window of Silero's model. */
    int FRAME = 512;

    /**
     * How likely a frame is speech.
     *
     * @param frame {@link #FRAME} samples, -1 to 1
     * @return 0 to 1; the gate keeps what it needs between frames, so frames are given in order
     */
    float probability(float[] frame);

    /** Forgets what came before: a new recording starts. */
    void reset();

    /** Frees what the gate holds (the model). */
    default void close() {
    }
}
