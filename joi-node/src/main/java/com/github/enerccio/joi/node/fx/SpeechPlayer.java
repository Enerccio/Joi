package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.protocol.http.AudioChunk;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Queue;

/**
 * Her speech: the pieces of a reply played one after another as they come, each a whole file (written to a temp file
 * for the player, deleted once played). Tells when the last one ended. Runs on the JavaFX thread.
 * <p>
 * On macOS a piece is played by the system's {@code afplay} in a process of its own: JavaFX's media library there reads a
 * player from an audio thread of its own, and a player that was stopped or finished crashed the whole node more than once
 * (SIGSEGV in {@code AudioQueueProcessingTapGetSourceAudio}, even when it was disposed late). Elsewhere the JavaFX player is used.
 */
class SpeechPlayer {

    /** How long a finished player is kept before it is disposed. */
    private static final double RETIRE_SECONDS = 2;

    private final Queue<Path> queue = new ArrayDeque<>();
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private MediaPlayer playing;
    /** The {@code afplay} that plays now (macOS). */
    private Process process;
    /** A piece is playing now, by either player. */
    private boolean busy;
    private Runnable onEnded = () -> {
    };

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    void play(AudioChunk chunk) {
        try {
            String extension = chunk.mimeType() != null && chunk.mimeType().contains("mpeg") ? ".mp3" : ".wav";
            Path file = Files.createTempFile("joi-speech", extension);
            Files.write(file, Base64.getDecoder().decode(chunk.data()));
            file.toFile().deleteOnExit();
            queue.add(file);
            if (!busy) {
                next();
            }
        } catch (IOException | IllegalArgumentException e) {
            // a piece that can't be played is skipped
            if (!busy && queue.isEmpty()) {
                onEnded.run();
            }
        }
    }

    /** Stops her speech (she was talked over). */
    void stop() {
        queue.clear();
        busy = false;
        if (process != null) {
            Process stopped = process;
            process = null;
            stopped.destroy();
        }
        if (playing != null) {
            retire(playing);
            playing = null;
        }
    }

    /**
     * A player that is done is stopped and disposed a moment later, not at once: on macOS the media library reads it
     * from an audio thread of its own, and disposing it under that thread crashed the whole node (SIGSEGV in
     * {@code AudioQueueProcessingTapGetSourceAudio}).
     */
    private static void retire(MediaPlayer player) {
        try {
            player.stop();
        } catch (RuntimeException e) {
            // already finished
        }
        PauseTransition later = new PauseTransition(Duration.seconds(RETIRE_SECONDS));
        later.setOnFinished(event -> player.dispose());
        later.play();
    }

    private void next() {
        Path file = queue.poll();
        if (file == null) {
            playing = null;
            busy = false;
            onEnded.run();
            return;
        }
        busy = true;
        if (MAC) {
            nextWithProcess(file);
            return;
        }
        MediaPlayer player = new MediaPlayer(new Media(file.toUri().toString()));
        playing = player;
        Runnable done = () -> {
            retire(player);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // removed on exit
            }
            next();
        };
        player.setOnEndOfMedia(done);
        player.setOnError(done);
        player.play();
    }

    /** Plays the piece with {@code afplay}; the next one follows when it ends (unless her speech was stopped meanwhile). */
    private void nextWithProcess(Path file) {
        Process started;
        try {
            started = new ProcessBuilder("afplay", file.toString()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            // no player: the piece is skipped
            deleteQuietly(file);
            next();
            return;
        }
        process = started;
        started.onExit().thenRun(() -> Platform.runLater(() -> {
            deleteQuietly(file);
            if (process == started) {
                process = null;
                next();
            }
        }));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // removed on exit
        }
    }
}
