package com.github.enerccio.joi.protocol.joia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code .joia} container: written and read back, clips skipped or refused, damage found. */
class JoiaFileTest {

    @TempDir
    File dir;

    private File clipFile(String name, int seed) throws IOException {
        File file = new File(dir, name);
        byte[] bytes = new byte[50_000 + seed];
        new Random(seed).nextBytes(bytes);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static JoiaMetadata.Clip clip(String uuid, String name) {
        return new JoiaMetadata.Clip(uuid, name, "You " + name + ".", List.of("tag"), true, false, 1.5, name + ".vrma",
                null, null, 2.0, List.of("hips"), false, null, null);
    }

    private JoiaMetadata meta() {
        JoiaMetadata.Animation jacks = new JoiaMetadata.Animation("a1", "Jacks", "Jumping jacks", true, List.of(
                new JoiaMetadata.Step("c1", false, 1, true),
                new JoiaMetadata.Step("c2", true, 1.25, true),
                new JoiaMetadata.Step("c1", false, 1, false)));
        return new JoiaMetadata(List.of(jacks), List.of(clip("c1", "start"), clip("c2", "jump")), "2026-11-19T12:00:00Z");
    }

    private File write(List<File> files) throws IOException {
        File joia = new File(dir, "a.joia");
        JoiaFile.write(joia, meta(), files);
        return joia;
    }

    @Test
    void theMetadataAndTheClipsAreReadBack() throws IOException {
        File start = clipFile("start.vrma", 1);
        File jump = clipFile("jump.vrma", 2);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        String hash = JoiaFile.write(stream, meta(), List.of(start, jump));
        File joia = new File(dir, "a.joia");
        Files.write(joia.toPath(), stream.toByteArray());

        List<File> out = new ArrayList<>();
        List<JoiaMetadata> seen = new ArrayList<>();
        JoiaFile.Contents contents = JoiaFile.read(joia, new JoiaFile.Reader() {
            @Override
            public void metadata(JoiaMetadata meta) {
                seen.add(meta);
            }

            @Override
            public File clip(int index, JoiaMetadata.Clip clip) {
                File file = new File(dir, "out-" + clip.uuid());
                out.add(file);
                return file;
            }
        });
        assertEquals(hash, contents.fileHash());
        assertEquals(List.of(0, 1), contents.read());
        assertEquals(1, seen.size());
        JoiaMetadata meta = contents.meta();
        assertEquals(meta().animations(), meta.animations());
        assertEquals(start.length(), meta.clip("c1").size());
        assertEquals(64, meta.clip("c2").sha256().length());
        assertEquals(1.25, meta.animations().getFirst().steps().get(1).speed());
        assertEquals("start", meta.clip("c1").name());
        assertArrayEquals(Files.readAllBytes(start.toPath()), Files.readAllBytes(out.get(0).toPath()));
        assertArrayEquals(Files.readAllBytes(jump.toPath()), Files.readAllBytes(out.get(1).toPath()));
    }

    @Test
    void aClipCanBeSkippedAndTheFileRefusedOnItsMetadata() throws IOException {
        File joia = write(List.of(clipFile("start.vrma", 1), clipFile("jump.vrma", 2)));
        JoiaFile.Contents contents = JoiaFile.read(joia, (index, clip) -> index == 1 ? new File(dir, "jump-out") : null);
        assertEquals(List.of(1), contents.read());
        assertFalse(new File(dir, "out-c1").exists());

        File never = new File(dir, "never");
        IOException refused = assertThrows(IOException.class, () -> JoiaFile.read(joia, new JoiaFile.Reader() {
            @Override
            public void metadata(JoiaMetadata meta) throws IOException {
                throw new IOException("refused");
            }

            @Override
            public File clip(int index, JoiaMetadata.Clip clip) {
                return never;
            }
        }));
        assertEquals("refused", refused.getMessage());
        assertFalse(never.exists());
    }

    @Test
    void aDamagedOrForeignFileIsRefused() throws IOException {
        File joia = write(List.of(clipFile("start.vrma", 1), clipFile("jump.vrma", 2)));
        try (RandomAccessFile file = new RandomAccessFile(joia, "rw")) {
            file.seek(file.length() - 10);
            file.write(file.read() ^ 0xff);
        }
        assertThrows(IOException.class, () -> JoiaFile.read(joia, (index, clip) -> new File(dir, "x" + index)));

        File other = clipFile("other.joia", 3);
        assertThrows(IOException.class, () -> JoiaFile.read(other, (index, clip) -> null));
        // a file for each clip
        assertThrows(IllegalArgumentException.class, () -> write(List.of(clipFile("start.vrma", 1))));
        assertTrue(JoiaFile.isJoia("Dance.JOIA"));
        assertEquals("a_b.joia", JoiaFile.fileName("a/b"));
        assertEquals("animations.joia", JoiaFile.fileName(" "));
    }
}
