package com.github.enerccio.joi.domain.components;

import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.google.gson.JsonObject;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guard against a call written as text. Some models (Gemma 4 12B) copy the call they planned into the words of
 * the reply: {@code play_animation(animation="Bashful")}. The words are spoken, so no call is made and the user hears
 * the code. The guard holds the first words of a round back until they show whether the reply opens with a call; when
 * it does, the round is thrown away and asked again.
 * <p>
 * It is a setting of the AI, off by default, kept in the AI's attributes under {@code "joi"} like
 * {@link TextModelTypes}: {@code {"joi": {"guardCallText": true}}}. Joi only; written through {@code AIService.save}.
 * A call later in the reply is not looked for.
 */
public final class CallTextGuard {

    static final String GUARD = "guardCallText";

    /** How many characters are held at most before the words are let through. */
    public static final int MAX_HOLD = 60;

    /** How often a round is asked again when it opens with a call. */
    public static final int MAX_RERUNS = 2;

    /** What the held words say. */
    public enum Verdict {
        /** Too little to tell. */
        WAIT,
        /** Words: show them. */
        PASS,
        /** A call written as text. */
        LEAK
    }

    // what a reply may open with before the call: spaces and the marks that wrap code
    private static final Pattern LEAD = Pattern.compile("^[\\s*`\"'>\\-]*");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]*");
    // the start of the arguments of an unknown name: nothing, or `name=`
    private static final Pattern NAMED_ARGUMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\s*=");
    private static final Pattern WORD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");

    private CallTextGuard() {
    }

    /**
     * @return whether the AI is set to have the first words of a round checked
     */
    public static boolean enabled(AI ai) {
        return ai != null && ai.getAttributes() != null && ai.getAttributes().get(TextModelTypes.ATTRIBUTE) instanceof JsonObject joi
                && joi.get(GUARD) != null && joi.get(GUARD).isJsonPrimitive() && joi.get(GUARD).getAsBoolean();
    }

    /**
     * Sets the guard, keeping whatever else Joi stored on the AI. Save the AI afterwards.
     */
    public static void set(AI ai, boolean on) {
        if (ai.getAttributes() == null) {
            ai.setAttributes(new JsonObject());
        }
        JsonObject joi = ai.getAttributes().get(TextModelTypes.ATTRIBUTE) instanceof JsonObject existing ? existing : new JsonObject();
        if (on) {
            joi.addProperty(GUARD, true);
        } else {
            joi.remove(GUARD);
        }
        ai.getAttributes().add(TextModelTypes.ATTRIBUTE, joi);
    }

    /**
     * Says what the words held so far are. A call is a name written as a name (letters, digits, underscore) with a
     * bracket right after it: of a known function, of anything with an underscore in it (a name nobody says), or
     * with arguments written as {@code name=}. A bracket after a space or a plain word ("Hmm (laughs)", "ok(smiles)")
     * is speech.
     *
     * @param held  the words of the round from its start
     * @param known whether the name is a function the model was offered
     * @param ended the round has no more words: nothing to wait for
     */
    public static Verdict check(String held, Predicate<String> known, boolean ended) {
        Verdict undecided = ended ? Verdict.PASS : Verdict.WAIT;
        Matcher lead = LEAD.matcher(held);
        String rest = lead.find() ? held.substring(lead.end()) : held;
        if (rest.isEmpty()) {
            return undecided;
        }
        if (rest.length() > MAX_HOLD) {
            return Verdict.PASS;
        }
        Matcher name = NAME.matcher(rest);
        if (!name.find()) {
            return Verdict.PASS;
        }
        if (name.end() == rest.length()) {
            // the word may go on or be followed by a bracket
            return undecided;
        }
        if (rest.charAt(name.end()) != '(') {
            return Verdict.PASS;
        }
        String id = name.group();
        String last = id.substring(id.lastIndexOf('.') + 1);
        if (known.test(id) || known.test(last) || id.contains("_")) {
            return Verdict.LEAK;
        }
        String arguments = rest.substring(name.end() + 1).stripLeading();
        if (arguments.isEmpty()) {
            return undecided;
        }
        if (arguments.charAt(0) == ')') {
            return Verdict.LEAK;
        }
        Matcher word = WORD.matcher(arguments);
        if (!word.find()) {
            return Verdict.PASS;
        }
        if (word.end() == arguments.length()) {
            return undecided;
        }
        return NAMED_ARGUMENT.matcher(arguments).find() ? Verdict.LEAK : Verdict.PASS;
    }
}
