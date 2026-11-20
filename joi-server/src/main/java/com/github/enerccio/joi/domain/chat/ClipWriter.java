package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.audio.WavJoiner;
import com.github.enerccio.joi.tts.http.BackendException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/**
 * Her reply spoken, kept as one clip: the pieces of speech are copied into a file as they come, so the whole of it is never
 * held in memory. Wav pieces are joined into one wav (their samples after one header, patched when the clip is closed), mp3
 * pieces one after another.
 */
public final class ClipWriter implements AutoCloseable {

    private final File file;
    private OutputStream out;
    private AudioKind kind;
    private WavJoiner.Format format;
    private long samples;
    private boolean closed;

    /** A clip written into the file. */
    public ClipWriter(File file) {
        this.file = file;
    }

    /** Adds the piece after the ones before it; a piece of another format than the first is left out. */
    public synchronized void add(byte[] audio) throws IOException, BackendException {
        if (closed || audio == null || audio.length == 0) {
            return;
        }
        AudioKind piece = AudioKind.of(audio);
        if (out == null) {
            kind = piece;
            out = new BufferedOutputStream(Files.newOutputStream(file.toPath()));
            if (kind == AudioKind.WAV) {
                format = WavJoiner.formatOf(audio);
                out.write(WavJoiner.header(format, 0));
            }
        } else if (piece != kind) {
            return;
        }
        if (kind == AudioKind.WAV) {
            if (!WavJoiner.formatOf(audio).equals(format)) {
                return;
            }
            byte[] data = WavJoiner.data(audio);
            out.write(data);
            samples += data.length;
        } else {
            out.write(audio);
        }
    }

    /** The file written. */
    public File file() {
        return file;
    }

    /** Whether any audio was written. */
    public synchronized boolean hasAudio() {
        return out != null;
    }

    /** The format of the clip, null when nothing was written. */
    public synchronized AudioKind kind() {
        return kind;
    }

    /** Finishes the file; it is the clip when {@link #hasAudio()}. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (out == null) {
            return;
        }
        out.close();
        if (kind == AudioKind.WAV) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(0);
                raf.write(WavJoiner.header(format, samples));
            }
        }
    }

    /** Closes it and removes what was written. */
    public synchronized void discard() {
        try {
            close();
        } catch (IOException e) {
            // going away anyway
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            // a temporary file in the folder of resources: the cleanup takes it
        }
    }
}
