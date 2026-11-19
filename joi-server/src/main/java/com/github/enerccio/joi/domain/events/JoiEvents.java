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

    /** She was put into another body (payload: {@code from}, {@code to}, the uuids of the bodies; null for none). */
    public static final String BODY_SWITCHED = "body.switched";

    /** How she looks in the body she is in was described anew (payload: {@code to}, the uuid of the body). */
    public static final String BODY_DESCRIBED = "body.described";

    /**
     * A part of the body she is in was switched (payload: {@code to}, the uuid of the body; {@code part}, the part's id;
     * {@code fromState}, {@code toState}, the ids of the states; {@code by}, who switched it: {@code user}).
     */
    public static final String PART_SWITCHED = "body.part_switched";

    private JoiEvents() {
    }
}
