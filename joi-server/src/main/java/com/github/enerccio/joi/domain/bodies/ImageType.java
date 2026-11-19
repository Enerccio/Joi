package com.github.enerccio.joi.domain.bodies;

/** The images a model takes (snapshots and the user's images), by their first bytes. */
public enum ImageType {
    PNG("image/png"), JPEG("image/jpeg"), WEBP("image/webp");

    private final String mimeType;

    ImageType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String mimeType() {
        return mimeType;
    }

    /** The type of the image that starts with these bytes (16 are enough), null when it is none of them. */
    public static ImageType of(byte[] head) {
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return PNG;
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return WEBP;
        }
        return null;
    }
}
