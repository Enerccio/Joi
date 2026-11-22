package com.github.enerccio.joi.node.fx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.scene.control.Labeled;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The commands of the screenshot mode ({@link NodeShots}): one {@code POST /do} with {@code {"cmd": "...", ...}}, answered
 * with {@code {"ok": true, ...}} or {@code {"ok": false, "error": "..."}}. Listens on the loopback address only.
 *
 * <ul>
 *   <li>{@code click {text}}: presses the button with this text</li>
 *   <li>{@code type {prompt, text}}: sets the field with this prompt text</li>
 *   <li>{@code combo {text}}: sets the editor of the editable combo box (the server address)</li>
 *   <li>{@code await {text, seconds?}}, {@code awaitStarting {start, seconds?}}: wait for a control; the second returns its {@code text}</li>
 *   <li>{@code texts}: the texts of the labels and buttons on screen ({@code texts}), to see where the app is</li>
 *   <li>{@code openMenu {path}}, {@code closeMenus}, {@code chooseMenu {path}}: the menu bar ({@code path}: the texts along the way)</li>
 *   <li>{@code snap {name}}: the main window with its open menus as {@code <out>/<name>.png};
 *       {@code snapDialog {name}}: the newest dialog alone; {@code dialogOpen}</li>
 *   <li>{@code quit}: ends the app</li>
 * </ul>
 */
final class ShotCommands {

    private static final Gson GSON = new Gson();

    private final NodeShots shots;
    private final int port;

    ShotCommands(NodeShots shots, int port) {
        this.shots = shots;
        this.port = port;
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/do", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonObject answer = new JsonObject();
        boolean quit = false;
        try {
            // a page in a browser can't send JSON here without asking first (CORS), which nothing answers
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!"POST".equals(exchange.getRequestMethod()) || type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new IllegalArgumentException("POST a JSON command (Content-Type: application/json)");
            }
            JsonObject command = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            String cmd = command.get("cmd").getAsString();
            quit = cmd.equals("quit");
            run(cmd, command, answer);
            answer.addProperty("ok", true);
        } catch (Throwable e) {
            answer.addProperty("ok", false);
            answer.addProperty("error", String.valueOf(e.getMessage()));
        }
        byte[] body = GSON.toJson(answer).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        if (quit) {
            // Chromium's and AWT's threads would keep the process alive
            Runtime.getRuntime().halt(0);
        }
    }

    private void run(String cmd, JsonObject in, JsonObject out) {
        switch (cmd) {
            case "click" -> shots.click(text(in, "text"));
            case "type" -> shots.type(text(in, "prompt"), text(in, "text"));
            case "combo" -> shots.typeInCombo(text(in, "text"));
            case "await" -> shots.await(text(in, "text"), seconds(in));
            case "awaitStarting" -> out.addProperty("text", shots.awaitStarting(text(in, "start"), seconds(in)));
            case "texts" -> {
                JsonArray list = new JsonArray();
                for (String text : shots.fx(() -> {
                    List<String> found = new ArrayList<>();
                    for (Labeled labeled : shots.find(Labeled.class)) {
                        if (labeled.isVisible() && labeled.getText() != null && !labeled.getText().isBlank()) {
                            found.add(labeled.getText());
                        }
                    }
                    return found;
                })) {
                    list.add(text);
                }
                out.add("texts", list);
            }
            case "openMenu" -> shots.openMenu(path(in));
            case "closeMenus" -> shots.closeMenus();
            case "chooseMenu" -> shots.chooseMenu(path(in));
            case "snap" -> shots.snap(text(in, "name"));
            case "snapDialog" -> shots.snapDialog(text(in, "name"));
            case "dialogOpen" -> out.addProperty("open", shots.dialogOpen());
            case "quit" -> {
            }
            default -> throw new IllegalArgumentException("unknown command " + cmd);
        }
    }

    private static String text(JsonObject in, String key) {
        if (!in.has(key)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return in.get(key).getAsString();
    }

    private static int seconds(JsonObject in) {
        return in.has("seconds") ? in.get("seconds").getAsInt() : 20;
    }

    private static String[] path(JsonObject in) {
        JsonArray array = in.getAsJsonArray("path");
        String[] path = new String[array.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = array.get(i).getAsString();
        }
        return path;
    }
}
