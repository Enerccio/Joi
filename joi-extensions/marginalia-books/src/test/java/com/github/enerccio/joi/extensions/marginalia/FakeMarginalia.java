package com.github.enerccio.joi.extensions.marginalia;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marginalia's API as far as the plugin uses it, on a port of localhost: two books, three commands (two only read, one writes), the
 * OpenAPI description of the two, a command that answers at once and one that answers with a run that is done on its second look.
 * It takes the key {@link #KEY} only.
 */
final class FakeMarginalia implements AutoCloseable {

    static final String KEY = "mrg_testprefix00_secret";

    /** A request it got: method, path and body. */
    record Request(String method, String path, String body) {
    }

    /** As Marginalia 1.9.2 lists them: the folder's uuid, name and path; b-3 is kept out of the list unless asked with ?all. */
    private static final String BOOKS = """
            [{"uuid":"b-1","name":"The Salt Road","folder":"f-2","folderName":"Novels","folderPath":"Writing/Novels","extendedApiOnly":false},
             {"uuid":"b-2","name":"Salt and Ash","folder":null,"folderName":null,"folderPath":null,"extendedApiOnly":false}""";
    private static final String HIDDEN = """
            ,{"uuid":"b-3","name":"Old Draft","folder":null,"folderName":null,"folderPath":null,"extendedApiOnly":true}""";
    private static final String COMMANDS = """
            [{"name":"get-outline","title":"Outline","description":"Gives the chapters of the book","async":false,"readOnly":true,"destructive":false,
              "forms":[{"anyArguments":false,"parameters":[]}]},
             {"name":"read-chapter","title":"Read a chapter","description":"Gives the text of a chapter","async":true,"readOnly":true,"destructive":false,
              "forms":[{"anyArguments":false,"parameters":[{"name":"number","type":"int","description":"Which chapter","optional":false}]}]},
             {"name":"search-parts","title":"Search","description":"Finds words in the parts","async":false,"readOnly":true,"destructive":false,
              "forms":[{"anyArguments":false,"parameters":[{"name":"query","type":"String","optional":false}]},
                       {"anyArguments":false,"parameters":[{"name":"query","type":"String","optional":false},{"name":"limit","type":"int","optional":true}]}]},
             {"name":"write-chapter","title":"Write a chapter","description":"Replaces a chapter","async":false,"readOnly":false,"destructive":true,
              "forms":[{"anyArguments":false,"parameters":[{"name":"text","type":"String","optional":false}]}]}]""";
    private static final String OPENAPI = """
            {"openapi":"3.1.0","paths":{
              "/books/{book}/commands/get-outline":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                "properties":{"arguments":{"type":"object","properties":{},"additionalProperties":false}}}}}}}},
              "/books/{book}/commands/read-chapter":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                "properties":{"arguments":{"type":"object","properties":{"number":{"type":"integer","description":"Which chapter","minimum":1}},
                "required":["number"],"additionalProperties":false}}}}}}}}}}""";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger looks = new AtomicInteger();
    /** The text of a chapter; a long one tests the cut. */
    volatile String chapter = "Chapter 1. Mara leaves the coast with the salt.";

    FakeMarginalia() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::handle);
        server.start();
    }

    /** The address of the fake, without {@code /api/v1}, as a user types it. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/api/v1/".length());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getQuery();
        requests.add(new Request(method, query == null ? path : path + "?" + query, body));
        if (!("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            answer(exchange, 401, "{\"error\":\"No valid key\"}");
            return;
        }
        switch (method + " " + path) {
            case "GET books" -> answer(exchange, 200, BOOKS + ("all=true".equals(exchange.getRequestURI().getQuery()) ? HIDDEN : "") + "]");
            case "GET commands" -> answer(exchange, 200, COMMANDS);
            case "GET openapi.json" -> answer(exchange, 200, OPENAPI);
            case "POST books/b-1/commands/get-outline" -> answer(exchange, 200,
                    "{\"command\":\"get-outline\",\"status\":\"done\",\"result\":\"1. Departure\\n2. The Ford\"}");
            case "POST books/b-1/commands/read-chapter" -> answer(exchange, 202,
                    "{\"run\":\"r1\",\"command\":\"read-chapter\",\"status\":\"running\",\"progress\":\"reading\"}");
            case "GET runs/r1" -> {
                if (looks.incrementAndGet() < 2) {
                    answer(exchange, 202, "{\"run\":\"r1\",\"command\":\"read-chapter\",\"status\":\"running\"}");
                } else {
                    answer(exchange, 200, "{\"run\":\"r1\",\"command\":\"read-chapter\",\"status\":\"done\",\"result\":"
                            + new com.google.gson.Gson().toJson(chapter) + "}");
                }
            }
            case "POST books/b-1/commands/list-parts" -> answer(exchange, 200,
                    "{\"command\":\"list-parts\",\"status\":\"done\",\"result\":\"1 h-1 Mara at the coast\\n2 h-2 The ford\"}");
            case "POST books/b-1/commands/get-part" -> answer(exchange, 200,
                    "{\"command\":\"get-part\",\"status\":\"done\",\"result\":\"Mara loads the salt.\"}");
            case "POST books/b-1/commands/write-chapter" -> answer(exchange, 403, "{\"error\":\"The key may only read\"}");
            default -> answer(exchange, 404, "{\"error\":\"No such call\"}");
        }
    }

    private static void answer(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
