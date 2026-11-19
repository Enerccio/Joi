package com.github.enerccio.joi.protocol.joia;

import com.github.enerccio.joi.protocol.joim.Cbor;
import com.github.enerccio.joi.protocol.joim.Sections;
import com.google.gson.JsonElement;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A {@code .joia} ("Joi animations"): animations with the clips they play, in one file, to share them or move them to
 * another server (see {@code DESIGN.md} → Bodies, animations; the format is documented in {@code joi-protocol/joia.md}).
 * <pre>
 * magic    u32  0x1DEA1AC7 ("IDEAL ACT")
 * major    u16  the format's major version: a reader refuses a newer one
 * minor    u16
 * sections, to the end of the file, as in a .joim ({@link Sections}):
 *   "META"  the metadata ({@link JoiaMetadata}), JSON as CBOR, first
 *   "VRMA"  a clip's file, one per clip, in the order of the metadata's clips
 * </pre>
 * Clips are streamed, never whole in memory, and a reader skips a kind it doesn't know.
 */
public final class JoiaFile {

    public static final int MAGIC = 0x1DEA1AC7;
    public static final int MAJOR = 1;
    public static final int MINOR = 0;
    public static final String EXTENSION = "joia";
    public static final String CONTENT_TYPE = "application/x-joi-animations";

    public static final String META = "META";
    public static final String VRMA = "VRMA";

    /** The largest metadata read into memory, against a damaged file. */
    private static final long MAX_META = 16L * 1024 * 1024;
    private static final int BUFFER = 1 << 16;

    /** Where a reader puts the clips it reads. */
    public interface Reader {

        /**
         * The metadata was read, before any clip: a reader that refuses the file throws here, before anything is
         * written.
         */
        default void metadata(JoiaMetadata meta) throws IOException {
        }

        /**
         * The file to copy the clip into, null to skip it.
         *
         * @param index its index in the metadata's clips
         */
        File clip(int index, JoiaMetadata.Clip clip) throws IOException;
    }

    /**
     * What a file held.
     *
     * @param read     the indexes of the clips read into files (not skipped)
     * @param fileHash the SHA-256 of the whole file in hex
     */
    public record Contents(int major, int minor, JoiaMetadata meta, List<Integer> read, String fileHash) {
    }

    private JoiaFile() {
    }

    /** Whether the name is of a {@code .joia}. */
    public static boolean isJoia(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith("." + EXTENSION);
    }

    /** The file's name for animations of the name: unsafe characters replaced, {@code animations} for none. */
    public static String fileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
        return (safe.isEmpty() ? "animations" : safe) + "." + EXTENSION;
    }

    /**
     * Writes the file into the stream (not closed). Each clip's hash and size are written into its metadata; a hash the
     * metadata already has is checked.
     *
     * @param files the clips' files, in the order of the metadata's clips
     * @return the SHA-256 of what was written, in hex
     */
    public static String write(OutputStream out, JoiaMetadata meta, List<File> files) throws IOException {
        if (files.size() != meta.clips().size()) {
            throw new IllegalArgumentException("a file for each clip: " + files.size() + " for " + meta.clips().size());
        }
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String hash = Sections.sha256(files.get(i));
            String expected = meta.clips().get(i).sha256();
            if (expected != null && !expected.equalsIgnoreCase(hash)) {
                throw new IOException("the clip changed: " + files.get(i).getName());
            }
            hashes.add(hash);
        }
        List<JoiaMetadata.Clip> clips = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            JoiaMetadata.Clip c = meta.clips().get(i);
            clips.add(new JoiaMetadata.Clip(c.uuid(), c.name(), c.description(), c.tags(), c.loop(), c.inPlace(), c.speed(),
                    c.fileName(), c.source(), c.take(), c.duration(), c.bones(), c.expressions(), files.get(i).length(), hashes.get(i)));
        }
        byte[] metaBytes = Cbor.encode(new JoiaMetadata(meta.animations(), clips, meta.exportedAt()).toJson());
        MessageDigest fileDigest = Sections.digest();
        // flushed, not closed: the stream is the caller's
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(out, fileDigest), BUFFER));
        data.writeInt(MAGIC);
        data.writeShort(MAJOR);
        data.writeShort(MINOR);
        Sections.head(data, META, metaBytes.length, Sections.digest().digest(metaBytes));
        data.write(metaBytes);
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            Sections.head(data, VRMA, file.length(), HexFormat.of().parseHex(hashes.get(i)));
            Sections.writeFile(data, file, bytes -> {
            });
        }
        data.flush();
        return HexFormat.of().formatHex(fileDigest.digest());
    }

    /** Writes the file to disk. */
    public static String write(File out, JoiaMetadata meta, List<File> files) throws IOException {
        try (OutputStream stream = Files.newOutputStream(out.toPath())) {
            return write(stream, meta, files);
        }
    }

    /**
     * Reads the file: its metadata, then each clip into the file the reader gives.
     *
     * @throws IOException when it is not a {@code .joia}, of a newer major version, damaged (a section's hash differs,
     *                     a clip's hash is not the metadata's), or has more clips than its metadata
     */
    public static Contents read(File in, Reader reader) throws IOException {
        Objects.requireNonNull(reader);
        MessageDigest fileDigest = Sections.digest();
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(
                new DigestInputStream(Files.newInputStream(in.toPath()), fileDigest), BUFFER))) {
            int magic;
            try {
                magic = data.readInt();
            } catch (EOFException e) {
                throw new IOException("not a Joi animations file");
            }
            if (magic != MAGIC) {
                throw new IOException("not a Joi animations file");
            }
            int major = data.readUnsignedShort();
            int minor = data.readUnsignedShort();
            if (major > MAJOR) {
                throw new IOException("the file is of a newer format (" + major + "." + minor + "): update Joi to read it");
            }
            JoiaMetadata meta = null;
            int clipIndex = 0;
            List<Integer> read = new ArrayList<>();
            byte[] kindBytes = new byte[4];
            while (true) {
                int first = data.read();
                if (first < 0) {
                    break;
                }
                kindBytes[0] = (byte) first;
                data.readFully(kindBytes, 1, 3);
                String kind = new String(kindBytes, StandardCharsets.US_ASCII);
                long length = data.readLong();
                byte[] hash = new byte[32];
                data.readFully(hash);
                if (length < 0) {
                    throw new IOException("a damaged section: " + kind);
                }
                if (META.equals(kind)) {
                    if (meta != null) {
                        throw new IOException("the file has two metadata");
                    }
                    if (length > MAX_META) {
                        throw new IOException("the metadata is too large");
                    }
                    byte[] bytes = new byte[(int) length];
                    data.readFully(bytes);
                    Sections.check(kind, hash, Sections.digest().digest(bytes));
                    JsonElement element = Cbor.decode(bytes);
                    if (!element.isJsonObject()) {
                        throw new IOException("the metadata is not an object");
                    }
                    meta = JoiaMetadata.fromJson(element.getAsJsonObject());
                    reader.metadata(meta);
                } else if (VRMA.equals(kind)) {
                    if (meta == null) {
                        throw new IOException("a clip before the metadata");
                    }
                    if (clipIndex >= meta.clips().size()) {
                        throw new IOException("more clips than the metadata lists");
                    }
                    JoiaMetadata.Clip clip = meta.clips().get(clipIndex);
                    if (clip.sha256() != null && !clip.sha256().equalsIgnoreCase(HexFormat.of().formatHex(hash))) {
                        throw new IOException("a clip is not the one its metadata lists: " + clip.name());
                    }
                    File target = reader.clip(clipIndex, clip);
                    if (target == null) {
                        Sections.skip(data, length);
                    } else {
                        Sections.copy(data, kind, length, hash, target, bytes -> {
                        });
                        read.add(clipIndex);
                    }
                    clipIndex++;
                } else {
                    Sections.skip(data, length);
                }
            }
            if (meta == null) {
                throw new IOException("the file has no metadata");
            }
            if (clipIndex != meta.clips().size()) {
                throw new IOException("the file has fewer clips than its metadata lists");
            }
            return new Contents(major, minor, meta, List.copyOf(read), HexFormat.of().formatHex(fileDigest.digest()));
        }
    }
}
