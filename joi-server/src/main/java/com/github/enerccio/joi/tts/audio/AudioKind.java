package com.github.enerccio.joi.tts.audio;

import com.github.enerccio.joi.tts.http.BackendException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which of the two formats a piece of audio is in, mp3 or wav, found by looking at its first bytes.
 */
public enum AudioKind {
    MP3("mp3", "audio/mpeg"), WAV("wav", "audio/wav");

    private final String extension;
    private final String mime;

    AudioKind(String extension, String mime) {
        this.extension = extension;
        this.mime = mime;
    }

    public String extension() {
        return extension;
    }

    public String mime() {
        return mime;
    }

    public static AudioKind of(byte[] audio) {
        return audio != null && audio.length > 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F' && audio[8] == 'W' ? WAV : MP3;
    }

    /** The kind of the audio of a file, from its first bytes. */
    public static AudioKind of(Path file) throws BackendException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] start = in.readNBytes(13);
            return start.length > 12 ? of(start) : MP3;
        } catch (IOException e) {
            throw new BackendException("The audio could not be read: " + e.getMessage(), e);
        }
    }

    /** The kind of a format name ({@code "wav"}, anything else is mp3). */
    public static AudioKind ofName(String name) {
        return "wav".equalsIgnoreCase(name) ? WAV : MP3;
    }

}
