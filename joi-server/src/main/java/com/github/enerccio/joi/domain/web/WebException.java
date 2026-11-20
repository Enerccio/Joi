package com.github.enerccio.joi.domain.web;

/** A search or a page that did not work; the message says why, and is safe to show to her and the user (no secrets). */
public class WebException extends Exception {

    private final int status;

    public WebException(String message) {
        this(message, 0, null);
    }

    public WebException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public WebException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status the other side answered with, 0 for none (it could not be reached). */
    public int status() {
        return status;
    }
}
