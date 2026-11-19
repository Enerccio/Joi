package com.github.enerccio.joi.protocol.http;

import java.util.List;

/** {@code GET /api/v1/jois}: the assistants of the user, by name. */
public record JoiList(List<JoiSummary> jois) {
}
