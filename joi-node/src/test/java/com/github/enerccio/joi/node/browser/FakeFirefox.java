package com.github.enerccio.joi.node.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Firefox and the extension, for tests: runs the real {@link NativeHost} on pipes in place of stdin and stdout, says hello when the
 * host says the node is there, and answers the node's requests with a handler (an exception is an error result).
 */
public final class FakeFirefox implements AutoCloseable {

    private final PipedOutputStream toHost = new PipedOutputStream();
    private final PipedInputStream fromHost = new PipedInputStream(1024 * 1024);
    private final List<String> states = new CopyOnWriteArrayList<>();
    private final BiFunction<String, JsonObject, JsonObject> handler;

    private FakeFirefox(BiFunction<String, JsonObject, JsonObject> handler) {
        this.handler = handler;
    }

    /** Starts the host for the node's socket, and the extension's side. */
    public static FakeFirefox start(Path socket, BiFunction<String, JsonObject, JsonObject> handler) throws IOException {
        FakeFirefox firefox = new FakeFirefox(handler);
        PipedInputStream hostIn = new PipedInputStream(firefox.toHost, 1024 * 1024);
        PipedOutputStream hostOut = new PipedOutputStream(firefox.fromHost);
        NativeHost host = new NativeHost(socket, hostIn, hostOut);
        Thread.ofVirtual().start(host::fromBrowser);
        Thread.ofVirtual().start(host::toBrowser);
        Thread.ofVirtual().start(firefox::read);
        return firefox;
    }

    /** The node states the host told, in order. */
    public List<String> states() {
        return states;
    }

    /** Waits until the node has the extension's hello. */
    public void awaitConnected(BrowserLink link) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!link.connected() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
    }

    private void read() {
        DataInputStream in = new DataInputStream(fromHost);
        try {
            while (true) {
                JsonObject message = JsonParser.parseString(new String(NativeHost.read(in), StandardCharsets.UTF_8)).getAsJsonObject();
                String type = message.get("type").getAsString();
                if ("node".equals(type)) {
                    String state = message.get("state").getAsString();
                    states.add(state);
                    if ("connected".equals(state)) {
                        JsonObject hello = new JsonObject();
                        hello.addProperty("type", "hello");
                        hello.addProperty("browser", "firefox");
                        hello.addProperty("version", "test");
                        send(hello);
                    }
                } else if ("request".equals(type)) {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "result");
                    result.add("id", message.get("id"));
                    try {
                        JsonObject data = handler.apply(message.get("op").getAsString(), message.getAsJsonObject("args"));
                        result.addProperty("ok", true);
                        result.add("data", data);
                    } catch (RuntimeException e) {
                        result.addProperty("ok", false);
                        result.addProperty("error", e.getMessage());
                    }
                    send(result);
                }
            }
        } catch (IOException | RuntimeException e) {
            // closed
        }
    }

    private synchronized void send(JsonObject message) throws IOException {
        toHost.write(NativeHost.frame(message.toString().getBytes(StandardCharsets.UTF_8)));
        toHost.flush();
    }

    /** Firefox closes the port: the host's stdin ends. */
    @Override
    public void close() {
        try {
            toHost.close();
        } catch (IOException e) {
            // closed
        }
        try {
            fromHost.close();
        } catch (IOException e) {
            // closed
        }
    }
}
