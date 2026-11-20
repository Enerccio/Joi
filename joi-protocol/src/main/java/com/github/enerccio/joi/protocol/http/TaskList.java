package com.github.enerccio.joi.protocol.http;

import java.util.List;

/** {@code GET /api/v1/jois/{id}/tasks} (since 1.6): her tasks, the newest first. */
public record TaskList(List<TaskInfo> tasks) {
}
