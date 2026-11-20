package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.text.Delivery;
import com.github.enerccio.joi.tts.text.HiggsText;
import com.github.enerccio.joi.tts.text.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What of a reply is spoken: the words without Markdown, the narration between asterisks ({@code *smiles*}, of any
 * length, over lines too, and one left open at the end) and emoji; split into pieces of whole sentences for a streamed reply; and put into the form the speech model reads (Higgs
 * gets the delivery of her mood as its emotion token).
 */
public final class SpeechText {

    /** Delivery strength of a mood: noticeable, not acted out. */
    static final double MOOD_INTENSITY = 0.5;

    private static final Pattern ACTION = Pattern.compile("(?<![*\\w])\\*(?![\\s*])[^*]+?(?<![\\s*])\\*(?![*\\w])");
    /** Narration the reply ends in before its closing asterisk (cut off, or the model forgot it). */
    private static final Pattern OPEN_ACTION = Pattern.compile("(?<![*\\w])\\*(?![\\s*])[^*]*$");
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
        String text = ACTION.matcher(reply).replaceAll(" ");
        text = OPEN_ACTION.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = MARKUP.matcher(text).replaceAll("");
        text = EMOJI.matcher(text).replaceAll("");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").strip();
    }

    /**
     * The text in pieces of whole sentences, each at most {@code max} characters when its sentences allow (a longer
     * sentence is a piece of its own). The first piece is one sentence, so that it is spoken soon.
     */
    public static List<String> pieces(String text, int max) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String paragraph : text.split("\\n+")) {
            for (String sentence : SENTENCE_END.split(paragraph.strip())) {
                if (sentence.isBlank()) {
                    continue;
                }
                boolean first = pieces.isEmpty();
                if (!piece.isEmpty() && (first || piece.length() + 1 + sentence.length() > max)) {
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
