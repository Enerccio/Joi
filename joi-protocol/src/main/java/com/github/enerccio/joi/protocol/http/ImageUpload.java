package com.github.enerccio.joi.protocol.http;

/**
 * {@code POST /api/v1/jois/{id}/images} (since 1.11): an image the server took, to be sent with a message ({@link ChatRequest#images()},
 * {@code Say.images}). The server keeps it as JPEG, scaled down so that its longest side is at most 1568 pixels; the model gets exactly
 * these bytes.
 *
 * @param id       the image's stable id
 * @param mimeType what it is kept as ({@code image/jpeg})
 * @param size     its size in bytes, as kept
 * @param width    its width in pixels, as kept
 * @param height   its height in pixels, as kept
 */
public record ImageUpload(String id, String mimeType, long size, int width, int height) {
}
