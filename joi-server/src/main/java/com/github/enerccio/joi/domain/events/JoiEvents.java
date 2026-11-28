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

    /**
     * A new body or new outfits became hers (payload: {@code bodies}, the uuids of the new bodies; {@code outfits}, the
     * ids of the new outfits). Told once, when the user is done editing; what is new while one waits is added to it.
     */
    public static final String BODY_ADDED = "body.added";

    /** The user put an outfit on her (payload: {@code to}, the uuid of the body; {@code outfit}, the outfit's id; null for none). */
    public static final String OUTFIT_CHANGED = "body.outfit_changed";

    /**
     * A task of hers ended (payload: {@code task}, its uuid; {@code status}: {@code done}, {@code failed}, {@code denied} or
     * {@code cancelled}). The text has what she is told, the result of a search included.
     */
    public static final String TASK_ENDED = "task.ended";

    /**
     * A summary was made (payload: {@code summary}, its uuid; {@code type}: {@code day}, {@code week}, {@code month} or
     * {@code year}; {@code partial}: a part of a day made from messages). The text has the summary, so she can react.
     */
    public static final String SUMMARY_CREATED = "summary.created";

    /**
     * The user changed her mood in her dialog (payload: {@code from}, {@code to}, the keys of the moods). She may react;
     * what she chose herself with {@code set_mood} is not an event.
     */
    public static final String MOOD_CHANGED = "mood.changed";

    /**
     * The user ended her stance in her dialog (payload: {@code from}, the uuid of the animation). She may react; what she
     * took or ended herself with {@code set_stance} and {@code end_stance} is not an event.
     */
    public static final String STANCE_ENDED = "stance.ended";

    private JoiEvents() {
    }
}
