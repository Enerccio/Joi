package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.protocol.http.AudioChunk;
import javafx.animation.PauseTransition;
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
 * for the media player, deleted once played). Tells when the last one ended. Runs on the JavaFX thread.
 */
class SpeechPlayer {

    /** How long a finished player is kept before it is disposed. */
    private static final double RETIRE_SECONDS = 2;

    private final Queue<Path> queue = new ArrayDeque<>();
    private MediaPlayer playing;
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
            if (playing == null) {
                next();
            }
        } catch (IOException | IllegalArgumentException e) {
            // a piece that can't be played is skipped
            if (playing == null && queue.isEmpty()) {
                onEnded.run();
            }
        }
    }

    /** Stops her speech (she was talked over). */
    void stop() {
        queue.clear();
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
            onEnded.run();
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
}
