package com.github.enerccio.joi.protocol.http;

/**
 * The {@code audio} event of a stream: one piece of the reply spoken, a sentence or a few, in order. Each piece is a
 * whole file of its type: play them one after another.
 *
 * @param index    0, 1, 2... in the order to play them
 * @param text     the words of the piece
 * @param mimeType the media type
 * @param data     the bytes, Base64
 */
public record AudioChunk(int index, String text, String mimeType, String data) {
}
