package com.github.enerccio.joi.protocol.http;

import java.util.List;

/** {@code GET /api/v1/jois/{id}/auto-approved} (since 1.7): the tools approved ahead of time for her. */
public record AutoApprovalList(List<AutoApproval> tools) {
}
