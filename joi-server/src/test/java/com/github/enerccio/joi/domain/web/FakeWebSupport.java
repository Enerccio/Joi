package com.github.enerccio.joi.domain.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@link FakeWeb} for the tests of other packages: it is package-private there. */
public final class FakeWebSupport {

    private FakeWebSupport() {
    }

    /** What it answers. */
    public record Answer(int status, String contentType, String body) {
    }

    /** A running fake server. */
    public static final class Web implements AutoCloseable {
        private final FakeWeb web;
        public final List<String> seen;

        Web(FakeWeb web) {
            this.web = web;
            this.seen = new java.util.AbstractList<>() {
                @Override
                public String get(int index) {
                    return web.seen.get(index).uri();
                }

                @Override
                public int size() {
                    return web.seen.size();
                }
            };
        }

        public String url() {
            return web.url();
        }

        /** What the last request was: its method, address and body. */
        public Last last() {
            FakeWeb.Seen seen = web.last();
            return new Last(seen.method(), seen.uri(), seen.body());
        }

        @Override
        public void close() {
            web.close();
        }
    }

    public record Last(String method, String uri, String body) {
    }

    public static Answer json(String body) {
        return new Answer(200, "application/json", body);
    }

    public static Answer html(String body) {
        return new Answer(200, "text/html; charset=utf-8", body);
    }

    public static Answer status(int status, String body) {
        return new Answer(status, "application/json", body);
    }

    public static Web start(Answer answer) throws IOException {
        return new Web(new FakeWeb(FakeWeb.Reply.status(answer.status(), answer.contentType(), answer.body())));
    }
}
