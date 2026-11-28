package com.github.enerccio.joi.domain.summary;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.marginalia.domain.service.impl.inference.tokenizer.EstimatedImageTokenizer;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * About how many tokens a message of her history takes in a prompt, without a tokenizer: what the summaries' budget and the
 * prompt's bound of her history count.
 */
public final class MessageTokens {

    /** Characters per token of the estimate of the words. */
    public static final int CHARS_PER_TOKEN = 4;

    private MessageTokens() {
    }

    /**
     * The exchange: its words, and the user's images (the notes written about them when a model that cannot see got those instead,
     * else their estimate by pixels kept on the message; an image of a message stored before the estimate counts as one of unknown
     * size). Her tools' pictures are not counted: they are given to the model only in their turn.
     */
    public static int of(ChatMessage message) {
        long chars = StringUtils.length(message.getInput()) + StringUtils.length(message.getResponse());
        long images = 0;
        List<String> notes = message.imageNoteTexts();
        if (!notes.isEmpty()) {
            for (String note : notes) {
                chars += StringUtils.length(note);
            }
        } else if (message.getImageTokens() != null) {
            images = message.getImageTokens();
        } else {
            images = message.imageUuids().size() * EstimatedImageTokenizer.UNKNOWN_TOKENS;
        }
        return (int) Math.min(Integer.MAX_VALUE, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + images);
    }

    /** The messages together. */
    public static long of(Collection<ChatMessage> messages) {
        long sum = 0;
        for (ChatMessage message : messages) {
            sum += of(message);
        }
        return sum;
    }
}
