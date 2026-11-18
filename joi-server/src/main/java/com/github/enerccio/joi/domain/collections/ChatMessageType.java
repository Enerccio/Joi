package com.github.enerccio.joi.domain.collections;

/**
 * What a {@code ChatMessage} answers: something the user said or typed, or something that happened (an event of a
 * node, a timer, a finished task). Stored by name, so the names never change.
 */
public enum ChatMessageType {

    /** The user spoke or typed: the input is their words. */
    USER,
    /** Something happened: the input describes the event, and the assistant may react to it. */
    EVENT
}
