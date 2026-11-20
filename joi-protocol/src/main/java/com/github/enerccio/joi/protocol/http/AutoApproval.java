package com.github.enerccio.joi.protocol.http;

/**
 * A tool of the server the user approved ahead of time for the assistant (since 1.7): its tasks are approved without
 * asking ("approve all of this tool").
 *
 * @param tool        the tool's name ({@code web_search}), the id to take the approval back with
 * @param name        the name to show
 * @param description what the tool does, for the user to see what they approved
 */
public record AutoApproval(String tool, String name, String description) {
}
