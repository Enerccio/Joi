package com.github.enerccio.joi.protocol.joim;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * The sections of Joi's files ({@code .joim}, {@code .joia}): after the file's head, to its end, each section is its
 * kind (4 ASCII bytes), its length (u64), the SHA-256 of its data (32 bytes) and its data. Sections are streamed, each
 * hash checked as it is read.
 */
public final class Sections {

    static final int BUFFER = 1 << 16;

    private Sections() {
    }

    /** Writes a section's head: its kind, length and hash. */
    public static void head(DataOutputStream data, String kind, long length, byte[] hash) throws IOException {
        byte[] kindBytes = kind.getBytes(StandardCharsets.US_ASCII);
        if (kindBytes.length != 4) {
            throw new IllegalArgumentException("a section's kind is 4 ASCII bytes: " + kind);
        }
        data.write(kindBytes);
        data.writeLong(length);
        data.write(hash);
    }

    /** Writes a file as a section's data (after its head), checking that it did not change. */
    public static void writeFile(OutputStream data, File file, LongConsumer progress) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[BUFFER];
            long total = 0;
            for (int read; (read = in.read(buffer)) > 0; ) {
                data.write(buffer, 0, read);
                total += read;
                progress.accept(read);
            }
            if (total != file.length()) {
                throw new IOException("the file changed while it was written: " + file.getName());
            }
        }
    }

    /** Copies a section's data into a file, checking its hash. */
    public static void copy(InputStream data, String kind, long length, byte[] hash, File out, LongConsumer progress) throws IOException {
        MessageDigest digest = digest();
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(out.toPath()), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            long left = length;
            while (left > 0) {
                int read = data.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new EOFException("the file ends inside a section: " + kind.strip());
                }
                digest.update(buffer, 0, read);
                file.write(buffer, 0, read);
                left -= read;
                progress.accept(read);
            }
        }
        check(kind, hash, digest.digest());
    }

    /** Reads a section's data through (not skipped: the whole file is hashed). */
    public static void skip(InputStream data, long length) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long left = length;
        while (left > 0) {
            int read = data.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new EOFException("the file ends inside a section");
            }
            left -= read;
        }
    }

    /** Refuses a section whose data is not what its hash says. */
    public static void check(String kind, byte[] expected, byte[] actual) throws IOException {
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("a damaged section: " + kind.strip());
        }
    }

    /** The SHA-256 of a file, in hex. */
    public static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file.toPath()), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
