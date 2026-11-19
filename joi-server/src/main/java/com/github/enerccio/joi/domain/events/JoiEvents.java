package com.github.enerccio.joi.domain.events;

/**
 * The kinds of the events the server tells an assistant about ({@code QueuedEvent.kind}, {@code ChatMessage.eventKind}).
 * A kind is stored, so it never changes.
 */
public final class JoiEvents {

    /** Her name changed (payload: {@code from}, {@code to}). */
    public static final String RENAMED = "persona.renamed";

    /** What she calls the user changed (payload: {@code from}, {@code to}). */
    public static final String USER_RENAMED = "persona.user_renamed";

    /** Her personality changed (payload: {@code from}, {@code to}, the keys). */
    public static final String PERSONALITY_CHANGED = "persona.personality";

    /** Her voice actor changed (payload: {@code from}, {@code to}, the ids of the actors; the text has the new description). */
    public static final String VOICE_CHANGED = "persona.voice";

    private JoiEvents() {
    }
}
