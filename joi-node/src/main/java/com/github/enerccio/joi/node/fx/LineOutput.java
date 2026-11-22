package com.github.enerccio.joi.node.fx;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Her voice through one output line of Java Sound that stays open between pieces. A player that is started for each piece
 * ({@code afplay}) starts the output device each time, which takes from a few hundred milliseconds to more than a second
 * and is not the same twice; so her mouth, started when the player was, led her voice. Here the device runs
 * already when a piece is written to it, and {@code started} is called when it does: the mouth starts with the sound.
 * <p>
 * Only wav files the JDK reads (any PCM, converted to 16 bit signed); {@link #open} returns null for anything else and the
 * caller plays it another way. Everything runs on one thread of its own; the callbacks come from it.
 */
class LineOutput {

    /** How long the line stays open with nothing to play (it keeps the output device running, and a device that runs is a battery that drains). */
    private static final long IDLE_SECONDS = 20;
    /** The line's own buffer: what is between a write and the sound. */
    private static final double BUFFER_SECONDS = 0.15;

    /** A piece ready to be played: the file read as 16 bit PCM. */
    static final class Piece implements AutoCloseable {
        private final AudioInputStream stream;

        private Piece(AudioInputStream stream) {
            this.stream = stream;
        }

        AudioFormat format() {
            return stream.getFormat();
        }

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException e) {
                // gone
            }
        }
    }

    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread worker = new Thread(task, "joi-speech-line");
        worker.setDaemon(true);
        return worker;
    });
    /** The line is closed from the watchdog and flushed from the caller of {@link #cancel()}: not only from {@link #thread}. */
    private volatile SourceDataLine line;
    private ScheduledFuture<?> idle;
    /**
     * Gives up on a piece whose sound has not been played by the time it should have been (the output device went: a monitor's speakers
     * unplugged, headphones taken off; a line on a device that is gone takes writes and never plays them, and {@code drain} never returns).
     */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread worker = new Thread(task, "joi-speech-watchdog");
        worker.setDaemon(true);
        return worker;
    });
    /** How long past its length a piece may take before its line is taken for dead. */
    private final long stallMillis;
    /** Opens the line (a test puts its own). */
    private final LineFactory factory;

    /** What opens an output line for a format. */
    interface LineFactory {
        SourceDataLine open(AudioFormat format, int bufferBytes) throws LineUnavailableException;
    }

    LineOutput() {
        this((format, bytes) -> {
            SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
            opened.open(format, bytes);
            return opened;
        }, 3000);
    }

    LineOutput(LineFactory factory, long stallMillis) {
        this.factory = factory;
        this.stallMillis = stallMillis;
    }
    /** Raised by {@link #cancel()}: the piece that is being written stops at the next chunk. */
    private volatile long generation;

    /** The file as PCM the line can take, or null when the JDK can't read it (an mp3, a format it doesn't know). */
    static Piece open(Path file) {
        AudioInputStream raw = null;
        try {
            raw = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat in = raw.getFormat();
            AudioFormat want = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, in.getSampleRate(), 16,
                    in.getChannels(), in.getChannels() * 2, in.getSampleRate(), false);
            AudioInputStream pcm = in.matches(want) ? raw : AudioSystem.getAudioInputStream(want, raw);
            return new Piece(pcm);
        } catch (Exception e) {
            // not a wav, or a format the JDK does not convert
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // gone
                }
            }
            return null;
        }
    }

    /**
     * Plays the piece. {@code started} is called once the line runs, just before the first sound is written, with the wall-clock time
     * (epoch milliseconds, taken on this thread) the sound begins at: the page puts her mouth on it, whenever it gets to the message; {@code ended}
     * when the sound has been played to its end (not when the piece was cancelled; {@code failed} then, if the line could not
     * be had or broke, and the piece did not play).
     */
    void play(Piece piece, java.util.function.LongConsumer started, Runnable ended, Runnable failed) {
        long mine = generation;
        thread.execute(() -> {
            AtomicBoolean stalled = new AtomicBoolean();
            ScheduledFuture<?> guard = null;
            try (piece) {
                if (idle != null) {
                    idle.cancel(false);
                }
                if (mine != generation) {
                    return;
                }
                SourceDataLine out = lineFor(piece.format());
                long frames = piece.stream.getFrameLength();
                long playMillis = frames > 0 ? (long) (frames * 1000.0 / piece.format().getFrameRate()) : 60_000;
                // a line that is dead is closed from here: a write or a drain that waits on it then ends
                guard = watchdog.schedule(() -> {
                    stalled.set(true);
                    SourceDataLine dead = line;
                    if (dead != null) {
                        dead.close();
                    }
                }, playMillis + stallMillis, TimeUnit.MILLISECONDS);
                byte[] buffer = new byte[4096 - 4096 % Math.max(1, piece.format().getFrameSize())];
                boolean first = true;
                for (int read; mine == generation && (read = piece.stream.read(buffer)) > 0; ) {
                    if (first) {
                        first = false;
                        started.accept(System.currentTimeMillis());
                    }
                    out.write(buffer, 0, read);
                    if (stalled.get() || !out.isOpen()) {
                        throw new IOException("The output line stopped taking sound");
                    }
                }
                if (mine == generation) {
                    out.drain();
                }
                if (stalled.get()) {
                    throw new IOException("The output line never finished playing");
                }
                if (mine == generation) {
                    ended.run();
                }
            } catch (IOException | LineUnavailableException | RuntimeException e) {
                closeLine();
                if (mine == generation) {
                    failed.run();
                }
            } finally {
                if (guard != null) {
                    guard.cancel(false);
                }
                idle = thread.schedule(this::closeLine, IDLE_SECONDS, TimeUnit.SECONDS);
            }
        });
    }

    /** Stops what is written now at once (the sound that is in the line's buffer is thrown away). */
    void cancel() {
        generation++;
        // not queued behind the piece that is written: a line that is stuck would never get to it
        SourceDataLine now = line;
        if (now != null) {
            try {
                now.flush();
            } catch (RuntimeException e) {
                // gone
            }
        }
    }

    void close() {
        generation++;
        thread.execute(this::closeLine);
        thread.shutdown();
        watchdog.shutdownNow();
    }

    private SourceDataLine lineFor(AudioFormat format) throws LineUnavailableException {
        if (line != null && line.isOpen() && line.getFormat().matches(format)) {
            if (!line.isRunning()) {
                line.start();
            }
            return line;
        }
        closeLine();
        int frame = format.getFrameSize();
        int bytes = (int) (format.getFrameRate() * BUFFER_SECONDS) * frame;
        SourceDataLine opened = factory.open(format, Math.max(bytes, frame * 1024));
        opened.start();
        line = opened;
        return opened;
    }

    private void closeLine() {
        SourceDataLine old = line;
        line = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException e) {
                // gone
            }
        }
    }
}
