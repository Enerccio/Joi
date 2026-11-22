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
import java.util.function.BiConsumer;

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

    /** A piece waiting to play: its number (the renderer's mouth reads the same piece by it) and its file. */
    private record Piece(long number, Path file) {
    }

    private final Queue<Piece> queue = new ArrayDeque<>();
    private long numbers;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** Wav pieces: one output line that stays open, so a piece starts at once (see {@link LineOutput}). */
    private final LineOutput lines = new LineOutput();
    /** Raised when her speech is stopped: what the line reports after that is not of this speech. */
    private long token;

    private MediaPlayer playing;
    /** The {@code afplay} that plays now (macOS). */
    private Process process;
    /** A piece is playing now, by either player. */
    private boolean busy;
    private Runnable onEnded = () -> {
    };
    private BiConsumer<Long, AudioChunk> onQueued = (number, chunk) -> {
    };
    private BiConsumer<Long, Long> onStarted = (number, at) -> {
    };
    private Runnable onStopped = () -> {
    };

    /** A piece came, before it plays: the number it is played under, and the piece (her mouth reads it from the sound). */
    void setOnQueued(BiConsumer<Long, AudioChunk> onQueued) {
        this.onQueued = onQueued;
    }

    /** The piece of that number plays now: its number and the wall-clock time (epoch milliseconds) its sound begins at. */
    void setOnStarted(BiConsumer<Long, Long> onStarted) {
        this.onStarted = onStarted;
    }

    /** Her speech is over or was stopped: nothing plays. */
    void setOnStopped(Runnable onStopped) {
        this.onStopped = onStopped;
    }

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    void play(AudioChunk chunk) {
        try {
            String extension = chunk.mimeType() != null && chunk.mimeType().contains("mpeg") ? ".mp3" : ".wav";
            Path file = Files.createTempFile("joi-speech", extension);
            Files.write(file, Base64.getDecoder().decode(chunk.data()));
            file.toFile().deleteOnExit();
            long number = ++numbers;
            queue.add(new Piece(number, file));
            onQueued.accept(number, chunk);
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
        token++;
        lines.cancel();
        onStopped.run();
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
        Piece piece = queue.poll();
        if (piece == null) {
            playing = null;
            busy = false;
            onStopped.run();
            onEnded.run();
            return;
        }
        busy = true;
        LineOutput.Piece pcm = LineOutput.open(piece.file());
        if (pcm != null) {
            nextOnLine(piece, pcm);
            return;
        }
        nextWithPlayer(piece);
    }

    /** A wav piece through the open line: the mouth starts when the line runs, just before the sound. */
    private void nextOnLine(Piece piece, LineOutput.Piece pcm) {
        long mine = token;
        lines.play(pcm,
                // the time is taken on the line's thread: the JavaFX thread may be busy drawing when this runs
                at -> Platform.runLater(() -> {
                    if (mine == token) {
                        onStarted.accept(piece.number(), at);
                    }
                }),
                () -> Platform.runLater(() -> {
                    if (mine == token) {
                        deleteQuietly(piece.file());
                        next();
                    }
                }),
                () -> Platform.runLater(() -> {
                    // no line (no output device, a broken one): the piece is played the old way
                    if (mine == token) {
                        nextWithPlayer(piece);
                    }
                }));
    }

    /** Any other piece (an mp3, or no line to be had): the system's player or JavaFX's, started for it. */
    private void nextWithPlayer(Piece piece) {
        Path file = piece.file();
        onStarted.accept(piece.number(), System.currentTimeMillis());
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
