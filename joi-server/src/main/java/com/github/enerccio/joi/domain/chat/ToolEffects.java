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

    private ToolEffects() {
    }

    /** Whether calling the function changes state (anything not known to be read-only does, a node's tools included). */
    public static boolean changesState(String function) {
        return function != null && !READ_ONLY.contains(function);
    }
}
