package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.InferenceSession;

/**
 * The cache key of Joi's requests to a model, as Marginalia does it: an {@link InferenceSession} open around the call that
 * starts the request, which the platform's inference service sends as the header {@value InferenceSession#HEADER}. Its value is
 * an opaque hash of the user, the action (the session's kind), the assistant and the model (its scope): the same four always
 * give the same value, so a server with a prompt cache (a proxy in front of llama.cpp keeping a slot per value, a provider that
 * routes by it) keeps the cache of her conversation apart from her summaries and her tasks, and from another assistant's.
 * <pre>
 * try (InferenceSession _ = JoiInference.open(JoiInference.CHAT, joi, ai)) {
 *     service.round(...);
 * }
 * </pre>
 * The user is the one of the thread's request scope (a turn, a task and a summary run in their owner's), and a call made
 * outside of one goes without the header.
 */
public final class JoiInference {

    /** Her turns: the user's words, events, the greeting; their prompts share her history. */
    public static final String CHAT = "chat";
    /** The summaries of her conversation, days, weeks... */
    public static final String SUMMARIZE = "summarize";
    /** Her tasks in the background (web search, reading pages). */
    public static final String TASK = "task";
    /** An image described for a model that cannot see it. */
    public static final String DESCRIBE_IMAGE = "describe-image";
    /** How a body looks, proposed by a multimodal model (no assistant: a body is the user's). */
    public static final String APPEARANCE = "appearance";

    private JoiInference() {
    }

    /**
     * Opens the scope on this thread; close it in the same thread (try-with-resources), after the request is started.
     *
     * @param action what the work is, one of the constants
     * @param joi    the assistant it is for, null for none
     * @param ai     the model it goes to, null when not known
     */
    public static InferenceSession open(String action, Joi joi, AI ai) {
        return InferenceSession.open(action, scope(joi, ai));
    }

    /** The scope of the session: the assistant and the model, so neither shares a cache with another. */
    static String scope(Joi joi, AI ai) {
        return "joi:" + (joi == null ? "-" : joi.getUuid()) + "/model:" + (ai == null ? "-" : ai.getUuid());
    }
}
