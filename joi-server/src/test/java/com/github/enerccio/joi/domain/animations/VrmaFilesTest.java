package com.github.enerccio.joi.domain.animations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What is read from a VRMA: only its JSON chunk. */
class VrmaFilesTest {

    @TempDir
    Path folder;

    @Test
    void aClipHasItsLengthTheBonesItMovesAndItsExpressions() throws Exception {
        File file = VrmaTestFiles.wave(folder.resolve("wave.vrma").toFile());
        VrmaFiles.Inspection inspection = VrmaFiles.inspect(file);
        assertThat(inspection).isNotNull();
        assertThat(inspection.duration()).isEqualTo(2.0);
        // the head is a bone of the clip but doesn't move
        assertThat(inspection.bones()).containsExactly("rightUpperArm", "rightLowerArm");
        assertThat(inspection.expressions()).isTrue();
    }

    @Test
    void anythingElseIsNoClip() throws Exception {
        File text = folder.resolve("wave.vrma").toFile();
        Files.writeString(text.toPath(), "not a clip");
        assertThat(VrmaFiles.inspect(text)).isNull();
        File empty = folder.resolve("empty.vrma").toFile();
        Files.write(empty.toPath(), new byte[0]);
        assertThat(VrmaFiles.inspect(empty)).isNull();
        assertThat(VrmaFiles.isVrma("Wave.VRMA")).isTrue();
        assertThat(VrmaFiles.isVrma("wave.glb")).isFalse();
    }
}
