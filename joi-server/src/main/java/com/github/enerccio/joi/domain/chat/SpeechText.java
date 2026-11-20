package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.text.Delivery;
import com.github.enerccio.joi.tts.text.HiggsText;
import com.github.enerccio.joi.tts.text.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What of a reply is spoken: the words without Markdown (the asterisks of an emphasis go, its words stay: she does not
 * narrate, and what is between asterisks is stressed, not an action) and emoji; split into pieces of whole sentences
 * for a streamed reply; and put into the form the speech model reads (Higgs gets the delivery of her mood as its
 * emotion token).
 */
public final class SpeechText {

    /** Delivery strength of a mood: noticeable, not acted out. */
    static final double MOOD_INTENSITY = 0.5;

    /** An emphasis, {@code *like this*}: a lone asterisk pair around words, which is not a product ({@code 2 * 3 * 4}). */
    private static final Pattern EMPHASIS = Pattern.compile("(?<![*\\w])\\*(?![\\s*])([^*]+?)(?<![\\s*])\\*(?![*\\w])");
    private static final Pattern MARKUP = Pattern.compile("(\\*\\*|__|`+|^#{1,6}\\s+|^\\s*[-*+]\\s+|^\\s*\\d+\\.\\s+)", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])[\"'”’)]*\\s+");

    private SpeechText() {
    }

    /** The words of the reply as they are said. */
    public static String clean(String reply) {
        if (reply == null) {
            return "";
        }
        String text = EMPHASIS.matcher(reply).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = MARKUP.matcher(text).replaceAll("");
        text = EMOJI.matcher(text).replaceAll("");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").strip();
    }

    /**
     * The text in pieces of whole sentences, each at most {@code max} characters when its sentences allow (a longer
     * sentence is a piece of its own). The first piece is one sentence, so that it is spoken soon, and each next one
     * at most twice as long as the one before (up to {@code max}): it is rendered while the one before plays, so the
     * pieces grow as the speech gets ahead and no gap opens after the first sentence.
     */
    public static List<String> pieces(String text, int max) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String paragraph : text.split("\\n+")) {
            for (String sentence : SENTENCE_END.split(paragraph.strip())) {
                if (sentence.isBlank()) {
                    continue;
                }
                int limit = pieces.isEmpty() ? 0 : Math.min(max, 2 * pieces.getLast().length() + 1);
                if (!piece.isEmpty() && piece.length() + 1 + sentence.length() > limit) {
                    pieces.add(piece.toString());
                    piece.setLength(0);
                }
                if (!piece.isEmpty()) {
                    piece.append(' ');
                }
                piece.append(sentence.strip());
            }
        }
        if (!piece.isEmpty()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /** The text as the model reads it: Higgs with the delivery of the mood, the others without the tags they can't voice. */
    public static String forModel(TtsModel model, String text, Delivery delivery) {
        if (model.isHiggs()) {
            return HiggsText.render(text, true, delivery, MOOD_INTENSITY);
        }
        return Tags.forModel(model.getModel()).isEmpty() ? Tags.strip(text) : text;
    }
}
