package com.github.enerccio.joi.domain.web;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A web server on the loopback that answers what a test says and keeps the requests it got. */
final class FakeWeb implements AutoCloseable {

    /** What it was asked. */
    record Seen(String method, String uri, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }
    }

    /** What it answers. */
    record Reply(int status, String contentType, String body, Map<String, String> headers, long delayMillis) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body, Map.of(), 0);
        }

        static Reply html(String body) {
            return new Reply(200, "text/html; charset=utf-8", body, Map.of(), 0);
        }

        static Reply status(int status, String contentType, String body) {
            return new Reply(status, contentType, body, Map.of(), 0);
        }
    }

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeWeb(Function<Seen, Reply> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "fake-web");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seen.add(request);
            Reply reply = answer.apply(request);
            try {
                if (reply.delayMillis() > 0) {
                    Thread.sleep(reply.delayMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    FakeWeb(Reply reply) throws IOException {
        this(request -> reply);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    Seen last() {
        return seen.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
