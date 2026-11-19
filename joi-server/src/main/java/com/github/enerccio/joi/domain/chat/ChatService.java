package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.domain.model.impl.AI;

/**
 * A chat turn with an assistant: her system prompt (persona, personality, today's mood) and the latest messages go to
 * her chat model, the exchange is stored as a {@link ChatMessage} (with its prompt), and the reply is spoken with her
 * voice. One turn of an assistant at a time; another one waits. Runs on the caller's thread, which needs the user (an
 * API call or a UI session).
 * <p>
 * The speech comes after the text (speaking sentences while the model still writes is roadmap step 3). A streamed
 * turn speaks the reply in {@link SpeechText#pieces pieces}, the first sentence first, so a node starts playing early.
 * Both models take their GPU locks per call ({@code GpuService}).
 */
public interface ChatService {

    /** How many of the latest messages go into the prompt (summaries come later, see DESIGN.md → Memory). */
    int HISTORY = 40;

    /** What a turn tells while it runs; on the caller's thread except the pieces of text (the model's thread). */
    interface Listener {
        /** A piece of the thinking of the model. */
        default void onReasoning(String delta) throws Exception {
        }

        /** A piece of the reply. */
        default void onText(String delta) throws Exception {
        }

        /** The reply is whole and stored; the speech follows. */
        default void onReply(ChatMessage message) throws Exception {
        }

        /** A piece of the reply spoken (only when asked for pieces). */
        default void onAudio(int index, String text, byte[] audio) throws Exception {
        }
    }

    /**
     * How the turn went.
     *
     * @param audio       the whole reply spoken (when asked for it whole), null otherwise
     * @param speechError why some or all of the speech is missing, null when nothing is
     */
    record Result(ChatMessage message, Mood mood, AI model, byte[] audio, String speechError, long promptTokens,
                  long completionTokens, long firstTokenMs, long textMs, long speechMs) {
    }

    /** The assistant has no chat model, or it is gone. */
    class NoModelException extends Exception {
        public NoModelException(String message) {
            super(message);
        }
    }

    /**
     * Talks to the assistant.
     *
     * @param node   the node the words came from, null for none
     * @param speak  whether to speak the reply (when she has a voice)
     * @param pieces speak in pieces ({@link Listener#onAudio}) instead of the whole reply ({@link Result#audio()})
     * @throws NoModelException when she has no chat model
     */
    Result chat(Joi joi, String text, String node, boolean speak, boolean pieces, Listener listener) throws Exception;

    /** Her chat model: hers, else the user's; null when none is set up (or it is gone). */
    AI chatModel(Joi joi) throws Exception;

    /** Her speech model, null for none. */
    TtsModel ttsModel(Joi joi) throws Exception;

    /** Her voice actor, null for none. */
    Actor voiceActor(Joi joi) throws Exception;

    /** Today's mood: the one she has since the day began ({@code dayBoundary}), else a new one rolled and stored. */
    Mood todaysMood(Joi joi) throws Exception;
}
