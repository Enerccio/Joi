package com.github.enerccio.joi.domain.chat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * What the server does with an image of the user before it keeps it (see {@code DESIGN.md} → Images in the chat): reads it from a
 * file (never from memory as a whole), turns it upright by its EXIF orientation, scales it down so that its longest side is at most
 * {@link #MAX_SIDE} pixels and writes it as JPEG. The model gets these bytes, and the same bytes in every prompt after, so the model's
 * cache of the prompt keeps them. Pure: no Spring, tested alone.
 */
public final class ChatImages {

    /** The longest side of an image the model gets. */
    public static final int MAX_SIDE = 1568;

    /** The largest upload. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** The most pixels an upload may have (decoding is the memory: four bytes a pixel, less with the subsampling below). */
    public static final long MAX_PIXELS = 80_000_000L;

    /** The JPEG quality of the image as kept. */
    static final float QUALITY = 0.88f;

    /** What was kept. */
    public record Info(int width, int height, long size) {
    }

    /** The upload is no image the server can read, or it is too large. */
    public static final class BadImageException extends IllegalArgumentException {
        private final boolean tooLarge;

        public BadImageException(String message) {
            this(message, false);
        }

        public BadImageException(String message, boolean tooLarge) {
            super(message);
            this.tooLarge = tooLarge;
        }

        /** Whether it was refused for its size in bytes (an HTTP 413), not for what it is. */
        public boolean tooLarge() {
            return tooLarge;
        }

        public BadImageException(String message, Throwable cause) {
            super(message, cause);
            this.tooLarge = false;
        }
    }

    private ChatImages() {
    }

    /**
     * Reads the image in {@code in} (PNG, JPEG, GIF, BMP or WebP, found by its content, never by what the client said) and writes
     * it to {@code out} as a JPEG at most {@link #MAX_SIDE} pixels on its longest side, upright, on white where it was transparent.
     *
     * @throws BadImageException when it is no readable image, has more than {@link #MAX_PIXELS} pixels, or is larger than
     *                           {@link #MAX_BYTES}
     */
    public static Info normalize(File in, File out) throws IOException {
        if (in.length() > MAX_BYTES) {
            throw new BadImageException("The image is larger than " + (MAX_BYTES / 1024 / 1024) + " MB", true);
        }
        int orientation = orientation(in);
        BufferedImage image;
        try (ImageInputStream input = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new BadImageException("This is not an image the server reads (PNG, JPEG, GIF, BMP and WebP are)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new BadImageException("The image has more than " + (MAX_PIXELS / 1_000_000) + " megapixels");
                }
                // a big photo is read at a part of its size: a reader that can does not decode what is thrown away
                ImageReadParam param = reader.getDefaultReadParam();
                int subsampling = Math.max(1, Math.max(width, height) / (MAX_SIDE * 2));
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                image = reader.read(0, param);
            } catch (BadImageException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new BadImageException("The image can't be read", e);
            } finally {
                reader.dispose();
            }
        }
        BufferedImage upright = scaled(turned(image, orientation));
        write(upright, out);
        return new Info(upright.getWidth(), upright.getHeight(), out.length());
    }

    /** The image scaled to {@link #MAX_SIDE} on its longest side (as it is when smaller), as RGB on white. */
    static BufferedImage scaled(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        if (scale < 1.0) {
            // halving first keeps a photo sharp: one bilinear step from four times the size drops pixels
            while (image.getWidth() >= w * 2 && image.getHeight() >= h * 2) {
                image = draw(image, image.getWidth() / 2, image.getHeight() / 2);
            }
        }
        return draw(image, w, h);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /**
     * The image turned as its EXIF orientation (1 to 8) says; anything else leaves it as it is. Pixel by pixel: the orientation of a
     * photo is rare enough, and the image is at most a few thousand pixels wide (it was read subsampled), so the loop is cheap and
     * plainly right.
     */
    static BufferedImage turned(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> {
                        tx = w - 1 - x;
                        ty = y;
                    }
                    case 3 -> {
                        tx = w - 1 - x;
                        ty = h - 1 - y;
                    }
                    case 4 -> {
                        tx = x;
                        ty = h - 1 - y;
                    }
                    case 5 -> {
                        tx = y;
                        ty = x;
                    }
                    case 6 -> {
                        tx = h - 1 - y;
                        ty = x;
                    }
                    case 7 -> {
                        tx = h - 1 - y;
                        ty = w - 1 - x;
                    }
                    default -> {
                        tx = y;
                        ty = w - 1 - x;
                    }
                }
                target.setRGB(tx, ty, image.getRGB(x, y));
            }
        }
        return target;
    }

    private static void write(BufferedImage image, File out) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** The mime type of the file's content: {@code image/jpeg} for what this class writes. */
    public static String mimeType(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(3);
            return head.length == 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 ? "image/jpeg" : "application/octet-stream";
        }
    }

    /**
     * The EXIF orientation (1 to 8) of a JPEG, 1 for a file that has none or is no JPEG. Reads only the first segments of the file.
     */
    static int orientation(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 4 || in.readUnsignedShort() != 0xFFD8) {
                return 1;
            }
            while (in.getFilePointer() + 4 <= in.length()) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA || marker == 0xFFD9) {
                    return 1;
                }
                int length = in.readUnsignedShort();
                if (length < 2) {
                    return 1;
                }
                if (marker == 0xFFE1 && length >= 16) {
                    byte[] segment = new byte[Math.min(length - 2, 64 * 1024)];
                    in.readFully(segment);
                    return orientation(segment);
                }
                in.seek(in.getFilePointer() + length - 2);
            }
        } catch (IOException | RuntimeException e) {
            // no orientation is no reason to refuse the image
        }
        return 1;
    }

    /** The orientation in an APP1 segment (without its marker and length), 1 when it is no EXIF or has none. */
    static int orientation(byte[] s) {
        if (s.length < 14 || s[0] != 'E' || s[1] != 'x' || s[2] != 'i' || s[3] != 'f' || s[4] != 0) {
            return 1;
        }
        int base = 6;
        boolean big = s[base] == 'M';
        if (!big && s[base] != 'I') {
            return 1;
        }
        long ifd = u32(s, base + 4, big);
        int at = (int) (base + ifd);
        if (ifd < 8 || at + 2 > s.length) {
            return 1;
        }
        int entries = u16(s, at, big);
        for (int i = 0; i < entries; i++) {
            int e = at + 2 + i * 12;
            if (e + 12 > s.length) {
                return 1;
            }
            if (u16(s, e, big) == 0x0112) {
                int value = u16(s, e + 8, big);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] s, int at, boolean big) {
        return big ? ((s[at] & 0xFF) << 8) | (s[at + 1] & 0xFF) : ((s[at + 1] & 0xFF) << 8) | (s[at] & 0xFF);
    }

    private static long u32(byte[] s, int at, boolean big) {
        return big ? ((long) u16(s, at, true) << 16) | u16(s, at + 2, true) : ((long) u16(s, at + 2, false) << 16) | u16(s, at, false);
    }

    /** The size of an image file in pixels without decoding it, null when it is no readable image. */
    public static int[] size(File file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
