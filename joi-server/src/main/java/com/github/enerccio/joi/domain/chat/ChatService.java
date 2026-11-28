package com.github.enerccio.joi.domain.chat;

import com.github.enerccio.joi.domain.chat.turn.TurnControllerEvent.Registration;
import com.github.enerccio.joi.domain.chat.turn.TurnEvent;
import com.github.enerccio.joi.domain.chat.turn.TurnEvents;
import com.github.enerccio.joi.domain.chat.turn.TurnInput;
import com.github.enerccio.joi.domain.chat.turn.TurnObserver;
import com.github.enerccio.joi.domain.chat.turn.TurnStepType;
import com.github.enerccio.joi.domain.model.impl.ChatMessage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.marginalia.domain.model.impl.AI;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * A chat turn with an assistant: her system prompt (persona, personality, today's mood) and the latest messages go to
 * her chat model, which may call functions on the way, the exchange is stored as a {@link ChatMessage} (with its
 * prompt), and the reply is spoken with her voice. One turn of an assistant at a time; another one waits.
 * <p>
 * A turn is asynchronous, like Marginalia's generation: steps ({@link TurnStepType}) that hand over to each other
 * through callbacks, and events ({@link TurnEvents}) where listeners ({@link TurnEvent}) change the turn and observers
 * ({@link TurnObserver}) watch it. Nothing waits on a thread: a listener or a function (a node's tool, an approval)
 * answers when it likes. Extensions and the server's own features (memories, tools, nodes) hook in here. The turn
 * runs in the request scope of the caller (its user), which must outlive the turn: an API call waits for the result.
 * <p>
 * The speech comes after the text (speaking sentences while the model still writes is roadmap step 3). A streamed
 * turn speaks the reply in {@link SpeechText#pieces pieces}, the first sentence first, so a node starts playing early.
 * Both models take their GPU locks per call ({@code GpuService}).
 */
public interface ChatService {

    /** A turn that waits for the one before it or runs now; {@code step} is null while it waits. */
    record RunningTurn(Joi joi, com.github.enerccio.joi.domain.chat.turn.TurnStepType step, Date since) {
    }

    /** What a turn tells while it runs, on the threads of the turn (the pieces of text on the model's thread). */
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

        /** She is about to call a function (before it runs; {@link ToolEffects} tells whether it changes anything). */
        default void onToolStart(String name, String arguments) throws Exception {
        }

        /** She called a function and it answered (or failed). */
        default void onToolCall(String name, String arguments, String result, boolean failed) throws Exception {
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

    /** The turns that wait or run now, the oldest first. */
    java.util.List<RunningTurn> running();

    /** The assistant has no chat model, or it is gone. */
    class NoModelException extends Exception {
        public NoModelException(String message) {
            super(message);
        }
    }

    /**
     * Images came with the message and she cannot look at them: her chat model does not take images and no model of the user that does
     * is set up to describe them for it. The turn is not started and nothing is stored, so the client tells the user.
     */
    class NoVisionException extends Exception {
        public NoVisionException(String message) {
            super(message);
        }
    }

    /**
     * Talks to the assistant. Returns at once; the turn starts when her turn before it is over.
     *
     * @param input what to say: {@link TurnInput#pieces()} speaks in pieces ({@link Listener#onAudio}) instead of the
     *              whole reply ({@link Result#audio()})
     * @return the result; it fails with the failure of the turn, and with a {@code CancellationException} when the
     * turn was stopped (cancelling the future stops it)
     * @throws NoModelException  when she has no chat model
     * @throws NoVisionException when images came with the words and she cannot look at them
     */
    CompletableFuture<Result> chat(Joi joi, TurnInput input, Listener listener) throws Exception;

    /**
     * Checks images that are to go with a message ({@link TurnInput#images()}) before a turn is asked for: she has a chat model, can
     * look at images, and each image is the user's own, kept, and not used by a message already.
     *
     * @return the ids, each once
     * @throws NoModelException     when she has no chat model
     * @throws NoVisionException    when she cannot look at images
     * @throws IllegalArgumentException when an image is not valid for a message
     */
    java.util.List<String> checkImages(Joi joi, java.util.List<String> images) throws Exception;

    /**
     * A listener of an event of every turn, called after the ones registered before it. Remove it when its owner goes
     * away.
     */
    Registration addEventListener(TurnEvents event, TurnEvent listener);

    /** An observer of an event of every turn, see {@link TurnObserver}. */
    Registration addEventObserver(TurnEvents event, TurnObserver observer);

    /**
     * What a conversation shows from the outside: a turn asked for, and the end of it. Called on the thread that asks
     * or ends the turn; it returns quickly, what it throws is logged.
     */
    interface Feed {
        /** A turn was asked for: it waits for the turn before it, or runs. {@code turn} names it until it finishes. */
        default void queued(String turn, TurnInput input, Date at) {
        }

        /** The turn is over: the message it stored, null when it was stopped or failed. */
        default void finished(String turn, ChatMessage message) {
        }

        /** A note of the system was written into the conversation ({@link #log}); it is not an exchange. */
        default void logged(ChatMessage message) {
        }

        /** The message got its clip: her reply spoken, see {@code ChatMessage.clipResourceId}. */
        default void voiced(ChatMessage message) {
        }
    }

    /**
     * Writes a note into the assistant's conversation: stored as a message of the type {@code LOG}, shown by the
     * transcript, sent to the clients (the watchers of the conversation, a node as the {@code log} frame), and never given
     * to the model. For what the user should see happened, such as an approval; not for what she must know (that is an
     * event).
     *
     * @param kind a short name of the kind of note ({@code task.approved}...)
     */
    ChatMessage log(Joi joi, String kind, String text) throws Exception;

    /** Calls the watcher with every note written for any assistant ({@link #log}); the nodes use it. */
    Registration watchLogs(java.util.function.Consumer<ChatMessage> watcher);

    /** Tells the watchers of the assistant's conversation that the message got its clip. */
    void voiced(ChatMessage message);

    /** Watches the assistant's conversation, see {@link Feed}. Remove it when its owner goes away. */
    Registration watch(Joi joi, Feed feed);

    /**
     * Runs the action when no turn of the assistant runs or waits any more, at once when there is none: the way to do
     * something after the reply that is going on is finished, and what is queued behind it. It runs on the thread that
     * ended the last turn, and sets up no scope of any user: that is for the action to do.
     */
    void whenIdle(Joi joi, Runnable action);

    /** Her chat model: hers, else the user's; null when none is set up (or it is gone). */
    AI chatModel(Joi joi) throws Exception;

    /** Her speech model, null for none. */
    TtsModel ttsModel(Joi joi) throws Exception;

    /** Her voice actor, null for none. */
    Actor voiceActor(Joi joi) throws Exception;

    /** Today's mood: the one she has since the day began ({@code dayBoundary}), else a new one rolled and stored. */
    Mood todaysMood(Joi joi) throws Exception;

    /**
     * Changes her mood now (it stays until the next day's roll): stored, written as a note into the conversation, sent to her
     * nodes ({@link #watchMoods}), and, when the user changed it, queued as an event so she can react. She changes it herself
     * with {@code set_mood}, which is her own choice and needs no event.
     *
     * @param key    the key of the mood
     * @param byUser true when the user changed it, false when she chose it
     * @throws IllegalArgumentException when there is no such mood
     */
    Mood changeMood(Joi joi, String key, boolean byUser) throws Exception;

    /** Calls the watcher when the mood of any assistant changed (the daily roll or {@link #changeMood}); the nodes use it. */
    Registration watchMoods(java.util.function.Consumer<Joi> watcher);
}
