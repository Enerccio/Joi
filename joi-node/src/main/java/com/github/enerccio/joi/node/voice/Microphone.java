package com.github.enerccio.joi.node.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;

/**
 * The default microphone, through Java Sound: 16 kHz, 16 bit, mono. It is open only while the user talks (one is opened
 * for each recording and closed at its end), so the system's microphone indicator is on only then. On macOS the app
 * needs the user's permission for the microphone ({@code NSMicrophoneUsageDescription} in the desktop app's plist); a
 * node started from a terminal or an IDE asks it for that program.
 */
public final class Microphone implements AudioInput {

    private static final AudioFormat FORMAT = new AudioFormat(SpeechGate.SAMPLE_RATE, 16, 1, true, false);

    private final TargetDataLine line;
    private final byte[] bytes = new byte[SpeechGate.FRAME * 2];
    private volatile boolean closed;

    private Microphone(TargetDataLine line) {
        this.line = line;
    }

    /** Whether this machine has a microphone that records in the format ({@code false} too when there is none). */
    public static boolean available() {
        return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
    }

    /** Opens the microphone and starts recording. */
    public static Microphone open() throws IOException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IOException("There is no microphone that records at 16 kHz.");
        }
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            // a buffer of a quarter of a second: the reader is not late for a frame
            line.open(FORMAT, SpeechGate.SAMPLE_RATE / 4 * 2);
            line.start();
            return new Microphone(line);
        } catch (LineUnavailableException | SecurityException | IllegalArgumentException e) {
            throw new IOException("The microphone cannot be opened: " + e.getMessage(), e);
        }
    }

    @Override
    public float[] read() {
        int have = 0;
        while (have < bytes.length) {
            if (closed) {
                return null;
            }
            int n = line.read(bytes, have, bytes.length - have);
            if (n <= 0 && closed) {
                return null;
            }
            have += Math.max(0, n);
        }
        float[] frame = new float[SpeechGate.FRAME];
        for (int i = 0; i < frame.length; i++) {
            // 16 bit little endian
            int sample = (bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8);
            frame[i] = sample / 32768f;
        }
        return frame;
    }

    @Override
    public void close() {
        closed = true;
        line.stop();
        line.close();
    }
}
