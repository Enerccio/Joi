package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.tts.audio.AudioKind;
import com.github.enerccio.joi.tts.audio.WavJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** The pieces of her speech copied into one clip. */
class ClipWriterTest {

    private static final WavJoiner.Format FORMAT = new WavJoiner.Format(1, 1, 8000, 16000, 2, 16);

    @TempDir
    File folder;

    @Test
    void wavPiecesBecomeOneWavWithOneHeader() throws Exception {
        File file = new File(folder, "clip.part");
        try (ClipWriter clip = new ClipWriter(file)) {
            clip.add(WavJoiner.of(FORMAT, new byte[1000]));
            clip.add(WavJoiner.of(FORMAT, new byte[600]));
            assertThat(clip.hasAudio()).isTrue();
            assertThat(clip.kind()).isEqualTo(AudioKind.WAV);
        }
        byte[] joined = Files.readAllBytes(file.toPath());
        assertThat(joined).hasSize(44 + 1600);
        assertThat(WavJoiner.data(joined)).hasSize(1600);
        assertThat(WavJoiner.formatOf(joined)).isEqualTo(FORMAT);
    }

    @Test
    void mp3PiecesFollowEachOther() throws Exception {
        File file = new File(folder, "clip.part");
        byte[] first = new byte[40];
        first[0] = (byte) 0xFF;
        try (ClipWriter clip = new ClipWriter(file)) {
            clip.add(first);
            clip.add(first);
            assertThat(clip.kind()).isEqualTo(AudioKind.MP3);
        }
        assertThat(file).hasSize(80);
    }

    @Test
    void nothingAddedLeavesNoFile() throws Exception {
        File file = new File(folder, "clip.part");
        ClipWriter clip = new ClipWriter(file);
        clip.add(null);
        clip.close();
        assertThat(clip.hasAudio()).isFalse();
        assertThat(file).doesNotExist();
    }
}
