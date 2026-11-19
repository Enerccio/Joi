package com.github.enerccio.joi.api;

import java.io.IOException;
import java.util.Map;

/** Where {@link JoiApi} writes its answer: one body, or a stream of server-sent events. */
public interface ApiSink {

    /** The whole answer. */
    void respond(int status, String contentType, byte[] body, Map<String, String> headers) throws IOException;

    /** Starts a stream of events (status 200, {@code text/event-stream}); the answer is then only events. */
    EventStream events() throws IOException;

    /** Server-sent events; the calls may come from several threads. */
    interface EventStream {

        /** {@code event: <name>} with {@code data: <json>}, flushed. */
        void send(String event, String json) throws IOException;

        /** A comment line ({@code : <text>}), which keeps the connection alive. */
        void comment(String text) throws IOException;
    }
}
