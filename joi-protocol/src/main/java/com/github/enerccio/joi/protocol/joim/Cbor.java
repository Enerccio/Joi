package com.github.enerccio.joi.protocol.joim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON as CBOR (RFC 8949), the binary JSON of a {@code .joim}'s metadata: what Gson's tree holds (objects, arrays,
 * text, numbers, booleans, null), with definite lengths. A whole number is an integer, any other a double. Byte
 * strings are read as text (base64 is not needed: the metadata has none); tags are read through.
 */
public final class Cbor {

    /** How deep a document may nest, against a damaged file. */
    private static final int MAX_DEPTH = 256;

    private Cbor() {
    }

    public static byte[] encode(JsonElement element) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(element, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static void write(JsonElement element, OutputStream out) throws IOException {
        if (element == null || element.isJsonNull()) {
            out.write(0xf6);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            head(out, 5, object.size());
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                text(out, entry.getKey());
                write(entry.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            head(out, 4, array.size());
            for (JsonElement item : array) {
                write(item, out);
            }
        } else {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.write(primitive.getAsBoolean() ? 0xf5 : 0xf4);
            } else if (primitive.isNumber()) {
                number(out, primitive.getAsBigDecimal());
            } else {
                text(out, primitive.getAsString());
            }
        }
    }

    private static void number(OutputStream out, BigDecimal value) throws IOException {
        BigDecimal whole = value.stripTrailingZeros();
        if (whole.scale() <= 0 && whole.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
            long n = whole.longValueExact();
            if (n >= 0) {
                head(out, 0, n);
            } else {
                head(out, 1, -1 - n);
            }
            return;
        }
        out.write(0xfb);
        long bits = Double.doubleToLongBits(value.doubleValue());
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (bits >>> shift) & 0xff);
        }
    }

    private static void text(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        head(out, 3, bytes.length);
        out.write(bytes);
    }

    private static void head(OutputStream out, int major, long value) throws IOException {
        int type = major << 5;
        if (value < 24) {
            out.write(type | (int) value);
        } else if (value < 0x100) {
            out.write(type | 24);
            out.write((int) value);
        } else if (value < 0x10000) {
            out.write(type | 25);
            out.write((int) (value >>> 8));
            out.write((int) value & 0xff);
        } else if (value < 0x100000000L) {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xff);
            }
        } else {
            out.write(type | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xff);
            }
        }
    }

    public static JsonElement decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        JsonElement element = read(in, 0);
        if (in.read() != -1) {
            throw new IOException("bytes after the CBOR document");
        }
        return element;
    }

    public static JsonElement read(InputStream stream) throws IOException {
        return read(stream instanceof DataInputStream data ? data : new DataInputStream(stream), 0);
    }

    private static JsonElement read(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("CBOR nested too deep");
        }
        int initial = in.read();
        if (initial < 0) {
            throw new EOFException("CBOR ended early");
        }
        int major = initial >>> 5;
        int info = initial & 0x1f;
        if (major == 7) {
            return simple(in, info);
        }
        long value = argument(in, info);
        return switch (major) {
            case 0 -> new JsonPrimitive(value);
            case 1 -> new JsonPrimitive(-1 - value);
            case 2, 3 -> new JsonPrimitive(new String(bytes(in, value), StandardCharsets.UTF_8));
            case 4 -> {
                JsonArray array = new JsonArray();
                for (long i = 0; i < value; i++) {
                    array.add(read(in, depth + 1));
                }
                yield array;
            }
            case 5 -> {
                JsonObject object = new JsonObject();
                for (long i = 0; i < value; i++) {
                    JsonElement key = read(in, depth + 1);
                    if (!key.isJsonPrimitive()) {
                        throw new IOException("a CBOR map key that is not text");
                    }
                    object.add(key.getAsString(), read(in, depth + 1));
                }
                yield object;
            }
            default -> read(in, depth + 1); // a tag: its item
        };
    }

    private static JsonElement simple(DataInputStream in, int info) throws IOException {
        return switch (info) {
            case 20 -> new JsonPrimitive(false);
            case 21 -> new JsonPrimitive(true);
            case 22, 23 -> JsonNull.INSTANCE;
            case 25 -> new JsonPrimitive(Float.float16ToFloat((short) in.readUnsignedShort()));
            case 26 -> new JsonPrimitive(in.readFloat());
            case 27 -> new JsonPrimitive(in.readDouble());
            default -> throw new IOException("an unknown CBOR simple value " + info);
        };
    }

    private static long argument(DataInputStream in, int info) throws IOException {
        if (info < 24) {
            return info;
        }
        long value = switch (info) {
            case 24 -> in.readUnsignedByte();
            case 25 -> in.readUnsignedShort();
            case 26 -> in.readInt() & 0xffffffffL;
            case 27 -> in.readLong();
            default -> throw new IOException("CBOR of indefinite length is not read");
        };
        if (value < 0) {
            throw new IOException("a CBOR length too large");
        }
        return value;
    }

    private static byte[] bytes(DataInputStream in, long length) throws IOException {
        if (length > Integer.MAX_VALUE - 16) {
            throw new IOException("a CBOR string too large");
        }
        byte[] bytes = in.readNBytes((int) length);
        if (bytes.length != length) {
            throw new EOFException("CBOR ended early");
        }
        return bytes;
    }
}
