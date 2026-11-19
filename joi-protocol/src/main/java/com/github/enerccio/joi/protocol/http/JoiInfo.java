package com.github.enerccio.joi.protocol.http;

/**
 * {@code GET /api/v1/jois/{id}}: an assistant as a client needs her.
 *
 * @param id          her stable id
 * @param name        her name
 * @param userName    what she calls the user
 * @param personality her personality
 * @param mood        the key of today's mood, null before her first chat of the day
 * @param wakeWord    the phrase that wakes her ({@code Hey <name>} when not set)
 * @param lastChatted when she was last talked to (ISO 8601), null for never
 * @param chatModel   the name of the model she chats with, null when none is set up (chat then fails)
 * @param voice       how she speaks, null when no speech is set up (replies are text only)
 */
public record JoiInfo(String id, String name, String userName, Personality personality, String mood, String wakeWord,
                      String lastChatted, String chatModel, Voice voice) {
}
