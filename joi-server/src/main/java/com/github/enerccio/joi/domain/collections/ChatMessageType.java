package com.github.enerccio.joi.domain.collections;

/**
 * What a {@code ChatMessage} answers: something the user said or typed, something that happened (an event of a
 * node, a timer, a finished task), or a note of the system that is not for the model. Stored by name, so the names never change.
 */
public enum ChatMessageType {

    /** The user spoke or typed: the input is their words. */
    USER,
    /** Something happened: the input describes the event, and the assistant may react to it. */
    EVENT,
    /**
     * A note of the system (the user approved a task, say): shown in the transcript and sent to clients, but not an
     * exchange. It is never given to the model, is not counted as her having been talked to, and is not summarized. The
     * input is the text and the event kind is the kind of the note.
     */
    LOG
}
