package com.github.enerccio.joi.extensions.marginalia;

/** A call of Marginalia's API that failed; the message is for the model and the user (never the key). */
public class MarginaliaException extends Exception {

    private final int status;

    public MarginaliaException(int status, String message) {
        super(message);
        this.status = status;
    }

    public MarginaliaException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** The HTTP status, 0 when there was no answer. */
    public int status() {
        return status;
    }
}
