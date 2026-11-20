package com.github.enerccio.joi.node;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Finds a Looking Glass through Looking Glass Bridge (its REST API on {@code 127.0.0.1:33334}, see {@code DESIGN.md} →
 * Avatar and Looking Glass): where the display is among the screens and its calibration, which the renderer page needs
 * to interleave her views for the display's lenses. Bridge is asked once per look and let go again.
 */
public final class LookingGlassBridge {

    /** Bridge's REST API. */
    public static final URI BRIDGE = URI.create("http://127.0.0.1:33334/");
    private static final String ORCHESTRATION = "joi-node";

    /**
     * A Looking Glass.
     *
     * @param serial      its serial ({@code LKG-E08597})
     * @param x           where it is among the screens, in points (the top left corner)
     * @param y           where it is among the screens, in points
     * @param width       its pixels across ({@code screenW} of the calibration)
     * @param height      its pixels down ({@code screenH})
     * @param calibration its calibration, as Bridge gives it ({@code pitch}, {@code slope}, {@code center}...)
     * @param quilt       the quilt Bridge suggests for it ({@code quiltX}, {@code quiltY}, {@code tileX}, {@code tileY})
     */
    public record Device(String serial, int x, int y, int width, int height, JsonObject calibration, JsonObject quilt) {

        /** What the renderer page needs: the calibration and the quilt (see {@code looking-glass.js}). */
        public JsonObject renderer() {
            JsonObject json = new JsonObject();
            json.add("calibration", calibration);
            JsonObject views = new JsonObject();
            views.addProperty("width", quilt.get("quiltX").getAsInt());
            views.addProperty("height", quilt.get("quiltY").getAsInt());
            views.addProperty("columns", quilt.get("tileX").getAsInt());
            views.addProperty("rows", quilt.get("tileY").getAsInt());
            json.add("quilt", views);
            return json;
        }
    }

    private final URI bridge;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public LookingGlassBridge() {
        this(BRIDGE);
    }

    LookingGlassBridge(URI bridge) {
        this.bridge = bridge;
    }

    /**
     * The first Looking Glass Bridge knows of.
     *
     * @throws IOException when Bridge does not answer or knows of none; the message says which, for the user
     */
    public Device find() throws IOException {
        JsonObject entered;
        try {
            entered = put("enter_orchestration", Map.of("name", ORCHESTRATION));
        } catch (ConnectException | HttpTimeoutException e) {
            throw new IOException("Looking Glass Bridge is not running (start it, then plug the display in)");
        }
        String token = value(entered.get("payload"));
        if (token == null) {
            throw new IOException("Looking Glass Bridge did not answer: " + value(entered.get("status")));
        }
        try {
            JsonObject devices = put("available_output_devices", Map.of("orchestration", token));
            JsonElement list = unwrap(devices.get("payload"));
            if (list != null && list.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : list.getAsJsonObject().entrySet()) {
                    Device device = device(unwrap(entry.getValue()));
                    if (device != null) {
                        return device;
                    }
                }
            }
            throw new IOException("Looking Glass Bridge sees no Looking Glass (is it plugged in?)");
        } finally {
            try {
                put("exit_orchestration", Map.of("orchestration", token));
            } catch (IOException e) {
                // Bridge lets it go by itself
            }
        }
    }

    /** A device of Bridge's list, null for a screen that is not a Looking Glass. */
    private static Device device(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject device = element.getAsJsonObject();
        String serial = value(device.get("hwid"));
        String calibrationText = value(device.get("calibration"));
        String quiltText = value(device.get("defaultQuilt"));
        JsonElement coords = unwrap(device.get("windowCoords"));
        if (serial == null || !serial.startsWith("LKG-") || calibrationText == null || calibrationText.isBlank()
                || quiltText == null || coords == null || !coords.isJsonObject()) {
            return null;
        }
        try {
            JsonObject calibration = JsonParser.parseString(calibrationText).getAsJsonObject();
            JsonObject quilt = JsonParser.parseString(quiltText).getAsJsonObject();
            if (!quilt.has("quiltX") || !quilt.has("quiltY") || !quilt.has("tileX") || !quilt.has("tileY")) {
                return null;
            }
            return new Device(serial, coords.getAsJsonObject().get("x").getAsInt(), coords.getAsJsonObject().get("y").getAsInt(),
                    number(calibration, "screenW"), number(calibration, "screenH"), calibration, quilt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(JsonObject calibration, String name) {
        return calibration.getAsJsonObject(name).get("value").getAsInt();
    }

    /** Bridge wraps each value as {@code {"name", "type", "value"}}. */
    private static JsonElement unwrap(JsonElement element) {
        if (element != null && element.isJsonObject() && element.getAsJsonObject().has("value")) {
            return element.getAsJsonObject().get("value");
        }
        return element;
    }

    private static String value(JsonElement element) {
        JsonElement value = unwrap(element);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private JsonObject put(String call, Map<String, String> body) throws IOException {
        JsonObject json = new JsonObject();
        body.forEach(json::addProperty);
        HttpRequest request = HttpRequest.newBuilder(bridge.resolve(call)).timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString(json.toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Looking Glass Bridge answered " + response.statusCode() + " to " + call);
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Looking Glass Bridge answered something else than JSON to " + call);
        }
    }
}
