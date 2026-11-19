package com.github.enerccio.joi.protocol.joim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * A {@code .joim} ("Joi model"): a processed model in one file, its VRM with what was decided on it (see
 * {@code DESIGN.md} → Bodies, export and import; the format is documented in {@code joi-protocol/joim.md}). A node
 * caches the bodies it renders in the same container.
 * <pre>
 * magic    u32  0x1DEA1A10 ("IDEAL AI 0")
 * major    u16  the format's major version: a reader refuses a newer one
 * minor    u16
 * profile  u8   0 the full model, 1 what a node renders
 * sections, to the end of the file, each:
 *   kind   4 ASCII bytes ("META", "VRM ")
 *   length u64  the bytes of its data
 *   hash   32   the SHA-256 of its data
 *   data
 * </pre>
 * Numbers are big-endian. The metadata ({@link #META}) is JSON as CBOR ({@link Cbor}); the model ({@link #VRM}) its
 * raw bytes (a VRM or a GLB). Sections are written and read as streams, the model never whole in memory, and a reader
 * skips a kind it doesn't know.
 */
public final class JoimFile {

    public static final int MAGIC = 0x1DEA1A10;
    public static final int MAJOR = 1;
    public static final int MINOR = 0;
    public static final String EXTENSION = "joim";
    public static final String CONTENT_TYPE = "application/x-joi-model";

    public static final String META = "META";
    public static final String VRM = "VRM ";

    /** The largest metadata read into memory, against a damaged file. */
    private static final long MAX_META = 64L * 1024 * 1024;
    private static final int BUFFER = Sections.BUFFER;

    /** Who the file is for. */
    public enum Profile {
        /** The model with everything decided on it, to share or keep. */
        FULL,
        /** Only what a renderer needs (the VRM, the toggles and their names, the state). */
        NODE
    }

    /**
     * What a file holds besides its model.
     *
     * @param modelHash the SHA-256 of the model in hex (checked), null when it was not read
     * @param fileHash  the SHA-256 of the whole file in hex
     */
    public record Contents(int major, int minor, Profile profile, JsonObject meta, boolean hasModel, String modelHash, String fileHash) {
    }

    private JoimFile() {
    }

    /** Whether the name is of a {@code .joim}. */
    public static boolean isJoim(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith("." + EXTENSION);
    }

    /**
     * Writes the file.
     *
     * @param modelHash the SHA-256 of the model file in hex, null to compute it (another pass over the file)
     * @param progress  takes the bytes of the model written, as they are
     * @return the SHA-256 of the file written, in hex
     */
    public static String write(File out, Profile profile, JsonObject meta, File model, String modelHash, LongConsumer progress) throws IOException {
        if (modelHash == null) {
            modelHash = sha256(model);
        }
        byte[] metaBytes = Cbor.encode(meta);
        MessageDigest fileDigest = digest();
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(Files.newOutputStream(out.toPath()), fileDigest), BUFFER))) {
            data.writeInt(MAGIC);
            data.writeShort(MAJOR);
            data.writeShort(MINOR);
            data.writeByte(profile.ordinal());
            Sections.head(data, META, metaBytes.length, digest().digest(metaBytes));
            data.write(metaBytes);
            Sections.head(data, VRM, model.length(), HexFormat.of().parseHex(modelHash));
            Sections.writeFile(data, model, progress);
        }
        return HexFormat.of().formatHex(fileDigest.digest());
    }

    /**
     * Reads the file: its metadata, and its model into {@code modelOut} (when not null).
     *
     * @param progress takes the bytes of the model read, as they are
     * @throws IOException when it is not a {@code .joim}, of a newer major version, or damaged (a section's hash differs)
     */
    public static Contents read(File in, File modelOut, LongConsumer progress) throws IOException {
        MessageDigest fileDigest = digest();
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(
                new DigestInputStream(Files.newInputStream(in.toPath()), fileDigest), BUFFER))) {
            int magic;
            try {
                magic = data.readInt();
            } catch (EOFException e) {
                throw new IOException("not a Joi model file");
            }
            if (magic != MAGIC) {
                throw new IOException("not a Joi model file");
            }
            int major = data.readUnsignedShort();
            int minor = data.readUnsignedShort();
            if (major > MAJOR) {
                throw new IOException("the file is of a newer format (" + major + "." + minor + "): update Joi to read it");
            }
            int profileIndex = data.readUnsignedByte();
            Profile profile = profileIndex < Profile.values().length ? Profile.values()[profileIndex] : Profile.FULL;
            JsonObject meta = null;
            String modelHash = null;
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
                    if (length > MAX_META) {
                        throw new IOException("the metadata is too large");
                    }
                    byte[] bytes = new byte[(int) length];
                    data.readFully(bytes);
                    Sections.check(kind, hash, digest().digest(bytes));
                    JsonElement element = Cbor.decode(bytes);
                    if (!element.isJsonObject()) {
                        throw new IOException("the metadata is not an object");
                    }
                    meta = element.getAsJsonObject();
                } else if (VRM.equals(kind) && modelOut != null) {
                    Sections.copy(data, VRM, length, hash, modelOut, progress);
                    modelHash = HexFormat.of().formatHex(hash);
                } else {
                    Sections.skip(data, length);
                }
            }
            if (meta == null) {
                throw new IOException("the file has no metadata");
            }
            return new Contents(major, minor, profile, meta, modelHash != null, modelHash, HexFormat.of().formatHex(fileDigest.digest()));
        }
    }

    /** The SHA-256 of a file, in hex. */
    public static String sha256(File file) throws IOException {
        return Sections.sha256(file);
    }

    private static MessageDigest digest() {
        return Sections.digest();
    }
}
