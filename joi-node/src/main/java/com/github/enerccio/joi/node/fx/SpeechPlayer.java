package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.protocol.http.AudioChunk;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

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
            playing.stop();
            playing.dispose();
            playing = null;
        }
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
            player.dispose();
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
