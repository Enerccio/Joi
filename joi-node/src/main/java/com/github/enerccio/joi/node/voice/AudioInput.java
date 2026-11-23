package com.github.enerccio.joi.node.voice;

import java.io.IOException;

/** A source of audio for the voice input: the microphone ({@link Microphone}), or a fake in a test. */
public interface AudioInput extends AutoCloseable {

    /**
     * The next frame of the recording, waiting for it.
     *
     * @return {@link SpeechGate#FRAME} samples at 16 kHz mono, -1 to 1; null when the input was closed
     */
    float[] read() throws IOException;

    /** Stops reading and lets the microphone go (the system's indicator goes off); a {@link #read} waiting returns null. */
    @Override
    void close();
}
