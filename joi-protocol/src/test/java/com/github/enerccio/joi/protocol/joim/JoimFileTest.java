package com.github.enerccio.joi.protocol.joim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code .joim} container: written and read back, refused when newer or damaged, unknown sections skipped. */
class JoimFileTest {

    @TempDir
    File dir;

    private static final String META = """
            {"model": {"name": "Ryuu", "chosen": true, "none": null, "groups": [{"id": "g", "states": []}],
             "count": 42, "negative": -7, "big": 5000000000, "ratio": 0.25, "text": "hřbet ✓"},
             "vrm": {"format": "VRM", "size": 300000}}""";

    private File model() throws IOException {
        File model = new File(dir, "model.vrm");
        byte[] bytes = new byte[300_000];
        new Random(1).nextBytes(bytes);
        Files.write(model.toPath(), bytes);
        return model;
    }

    @Test
    void theMetadataAndTheModelAreReadBack() throws IOException {
        File model = model();
        JsonObject meta = JsonParser.parseString(META).getAsJsonObject();
        File joim = new File(dir, "a.joim");
        AtomicLong written = new AtomicLong();
        String fileHash = JoimFile.write(joim, JoimFile.Profile.FULL, meta, model, null, written::addAndGet);
        assertEquals(model.length(), written.get());
        assertEquals(JoimFile.sha256(joim), fileHash);

        File out = new File(dir, "out.vrm");
        AtomicLong read = new AtomicLong();
        JoimFile.Contents contents = JoimFile.read(joim, out, read::addAndGet);
        assertEquals(JoimFile.MAJOR, contents.major());
        assertEquals(JoimFile.Profile.FULL, contents.profile());
        assertEquals(meta, contents.meta());
        assertTrue(contents.hasModel());
        assertEquals(model.length(), read.get());
        assertArrayEquals(Files.readAllBytes(model.toPath()), Files.readAllBytes(out.toPath()));
        assertEquals(JoimFile.sha256(model), JoimFile.sha256(out));
        assertEquals(JoimFile.sha256(model), contents.modelHash());
        assertEquals(fileHash, contents.fileHash());

        // without a place for the model, only the metadata is read
        assertFalse(JoimFile.read(joim, null, bytes -> {
        }).hasModel());
    }

    @Test
    void cborKeepsTheJsonValues() throws IOException {
        JsonObject meta = JsonParser.parseString(META).getAsJsonObject();
        assertEquals(meta, Cbor.decode(Cbor.encode(meta)));
        // a small integer is one byte, as CBOR says
        assertArrayEquals(new byte[]{0x18, 42}, Cbor.encode(JsonParser.parseString("42")));
        assertArrayEquals(new byte[]{(byte) 0xf5}, Cbor.encode(JsonParser.parseString("true")));
    }

    @Test
    void aNewerMajorVersionADamagedSectionAndAnotherFileAreRefused() throws IOException {
        File model = model();
        File joim = new File(dir, "a.joim");
        JoimFile.write(joim, JoimFile.Profile.FULL, JsonParser.parseString(META).getAsJsonObject(), model, null, bytes -> {
        });

        try (RandomAccessFile file = new RandomAccessFile(joim, "rw")) {
            file.seek(file.length() - 10);
            file.write(0x55 ^ file.read());
        }
        IOException damaged = assertThrows(IOException.class, () -> JoimFile.read(joim, new File(dir, "x.vrm"), bytes -> {
        }));
        assertTrue(damaged.getMessage().contains("damaged"), damaged.getMessage());

        try (RandomAccessFile file = new RandomAccessFile(joim, "rw")) {
            file.seek(4);
            file.writeShort(JoimFile.MAJOR + 1);
        }
        IOException newer = assertThrows(IOException.class, () -> JoimFile.read(joim, null, bytes -> {
        }));
        assertTrue(newer.getMessage().contains("newer"), newer.getMessage());

        assertThrows(IOException.class, () -> JoimFile.read(model, null, bytes -> {
        }));
    }

    @Test
    void aSectionOfAnUnknownKindIsSkipped() throws IOException {
        byte[] meta = Cbor.encode(JsonParser.parseString("{\"model\": {\"name\": \"x\"}}"));
        File joim = new File(dir, "b.joim");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(joim.toPath()))) {
            out.writeInt(JoimFile.MAGIC);
            out.writeShort(JoimFile.MAJOR);
            out.writeShort(JoimFile.MINOR + 3);
            out.writeByte(1);
            out.write("ANIM".getBytes(StandardCharsets.US_ASCII));
            out.writeLong(5);
            out.write(new byte[32]);
            out.write(new byte[5]);
            out.write(JoimFile.META.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(meta.length);
            out.write(java.security.MessageDigest.getInstance("SHA-256").digest(meta));
            out.write(meta);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        JoimFile.Contents contents = JoimFile.read(joim, new File(dir, "none.vrm"), bytes -> {
        });
        assertEquals(JoimFile.Profile.NODE, contents.profile());
        assertEquals("x", contents.meta().getAsJsonObject("model").get("name").getAsString());
        assertFalse(contents.hasModel());
    }
}
