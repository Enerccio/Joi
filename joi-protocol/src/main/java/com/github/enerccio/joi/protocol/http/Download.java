package com.github.enerccio.joi.protocol.http;

/**
 * A file of the server to download: a {@code .joim} of a model or a {@code .joia} of a body's animations. A client
 * caches it by its hash and downloads it again only when the hash changed.
 *
 * @param url       where to download it (GET, the same credentials; it answers 304 to {@code If-None-Match} with the hash)
 * @param sha256    the SHA-256 of the file (hex), also its ETag
 * @param size      its bytes
 * @param writtenAt when it was written, ISO 8601
 * @param current   whether it holds what is set up now; false when it changed since and the file is written again
 *                  when the user exits its dialog
 */
public record Download(String url, String sha256, long size, String writtenAt, boolean current) {
}
