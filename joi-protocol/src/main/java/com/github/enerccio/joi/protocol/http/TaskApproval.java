package com.github.enerccio.joi.protocol.http;

/**
 * The body of {@code POST /api/v1/jois/{id}/tasks/{task}/approve} (since 1.7), may be left out.
 *
 * @param always true: the user approves every use of the task's tool ahead of time; false or null: this task only
 */
public record TaskApproval(Boolean always) {
}
