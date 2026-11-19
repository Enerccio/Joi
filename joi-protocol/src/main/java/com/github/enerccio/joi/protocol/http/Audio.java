package com.github.enerccio.joi.protocol.http;

/**
 * Spoken audio.
 *
 * @param mimeType its media type ({@code audio/mpeg}, {@code audio/wav}...)
 * @param data     the bytes, Base64
 */
public record Audio(String mimeType, String data) {
}
