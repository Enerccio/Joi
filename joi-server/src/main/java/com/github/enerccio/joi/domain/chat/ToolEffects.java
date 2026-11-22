package com.github.enerccio.joi.domain.chat;

import java.util.Set;

/**
 * Which of her functions change something (her body, her memory, a task, the user's machine) and which only look or play. A
 * turn that has done something that changes state is finished even when its client is gone ({@code NodeServiceImpl.closed});
 * one that has only looked is dropped with it. A function is taken to change state unless it is listed here, so a new one is
 * finished by default: list it only when it changes nothing.
 */
public final class ToolEffects {

    /** The functions that change nothing: a gesture, a look at what she has, a look at what she remembers. */
    private static final Set<String> READ_ONLY = Set.of(AnimationTools.PLAY_ANIMATION, BodyTools.LIST_OUTFITS,
            BodyTools.LIST_BODIES, MemoryTools.RECALL, WorkspaceTools.LIST);

    /** The functions whose result says nothing the model could use: a gesture, a mood, a notice on the user's screen. */
    private static final Set<String> NO_ANSWER = Set.of(AnimationTools.PLAY_ANIMATION, MoodTools.SET_MOOD);

    private ToolEffects() {
    }

    /**
     * Whether a function only does something and its result gives the model nothing to say about (so a round that has already said its
     * words and made only such calls needs no round after them): a gesture, a mood, and a node's {@code notify}.
     */
    public static boolean needsNoAnswer(String function) {
        return function != null && (NO_ANSWER.contains(function) || function.endsWith("__notify"));
    }

    /** Whether calling the function changes state (anything not known to be read-only does, a node's tools included). */
    public static boolean changesState(String function) {
        return function != null && !READ_ONLY.contains(function);
    }
}
