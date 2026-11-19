package com.github.enerccio.joi.protocol.http;

/**
 * Her reply: the answer of a chat, or the {@code reply} event of a stream (its audio then comes in {@code audio} events
 * and is null here).
 *
 * @param messageId   the id of the stored message
 * @param text        what she said
 * @param reasoning   the thinking of the model, null unless asked for (or when it did not think)
 * @param mood        the key of her mood
 * @param model       the name of the model that answered
 * @param audio       the reply spoken, null when not asked for, when she has no voice or when the speech failed
 * @param speechError why there is no audio although it was asked for, null otherwise
 * @param usage       the tokens and the times
 */
public record ChatReply(String messageId, String text, String reasoning, String mood, String model, Audio audio,
                        String speechError, Usage usage) {
}
