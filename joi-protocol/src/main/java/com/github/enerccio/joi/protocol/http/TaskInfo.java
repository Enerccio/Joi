package com.github.enerccio.joi.protocol.http;

import java.util.List;

/**
 * A task she started with a tool of the server ({@code web_search}, {@code fetch_page}), since 1.6. It runs after
 * her reply, or waits ({@code waiting}) until the user approves it.
 *
 * @param id       the task's stable id
 * @param title    a short name
 * @param tool     the function that started it ({@code web_search}, {@code fetch_page})
 * @param status   {@code queued}, {@code running}, {@code waiting} (for the user's answer), {@code done}, {@code failed} or
 *                 {@code cancelled} (also what the user denied)
 * @param question what she asked for
 * @param url      the page to read, null for a search
 * @param result   what the task found, null until it is done
 * @param error    why it failed, was cancelled or denied, null otherwise
 * @param created  when she asked (ISO 8601 date and time)
 * @param started  when it started running, null before
 * @param finished when it ended, null before
 * @param steps    its transcript, oldest first; only in the answer for one task, null in the list
 */
public record TaskInfo(String id, String title, String tool, String status, String question, String url, String result,
                       String error, String created, String started, String finished, List<TaskStep> steps) {
}
