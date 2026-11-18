package com.github.enerccio.joi.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The log of the speech code (the servers, the voices), one name to filter on. */
public final class TtsLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.github.enerccio.joi.tts");

    private TtsLog() {
    }

    public static Logger get() {
        return LOG;
    }
}
