package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * {@code GET /api/v1/admin/extensions} (since 1.13): the extensions of the server.
 *
 * @param extensions in the order the server installed them
 */
public record ExtensionList(List<ExtensionInfo> extensions) {
}
