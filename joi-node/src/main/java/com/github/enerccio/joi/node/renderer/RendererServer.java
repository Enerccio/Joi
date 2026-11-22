package com.github.enerccio.joi.node.renderer;

import com.github.enerccio.joi.protocol.http.BodyToggle;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Serves the renderer page (the {@code joi-renderer/} resources, built from {@code src/main/renderer}) and her body
 * ({@link BodyFiles}) on {@code 127.0.0.1}, at a random port under a secret path, and drives every page open on it:
 * what to show and play goes out as server-sent events ({@code events}), the end of an animation that does not loop
 * comes back as a POST ({@code ended}). See {@code src/main/renderer/src/main.js} for the messages. Any browser can
 * show the page: JCEF in the app, or a normal one for development. An animation that does not loop also ends when no
 * page tells within its length and a few seconds (none is open, or it hangs), so her states go on without a renderer.
 * <p>
 * The page at {@link #lookingGlassUrl()} renders her for a Looking Glass, with the calibration given to
 * {@link #lookingGlass} (served as {@code looking-glass.json}).
 */
public class RendererServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RendererServer.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PAGE = "joi-renderer/";
    private static final long MAX_POST = 64 * 1024;
    /** How much longer than its length an animation may take before it is taken as ended. */
    private static final double GRACE_SECONDS = 3;
    /** The length of an animation whose clips do not say theirs. */
    private static final double UNKNOWN_SECONDS = 10;

    private final HttpServer http;
    private final String secret;
    private final List<BlockingQueue<String>> pages = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Joi renderer watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private BodyFiles body = BodyFiles.none();
    /** The last model and play messages, sent again to a page that opens later. */
    private String modelMessage;
    private String playMessage;
    private long playing;
    /** How her eyes are and her mood, kept for the pages that open later. */
    private String eyesMessage;
    private String moodMessage;
    private boolean playingLoops = true;
    /** What the Looking Glass page renders with, null for none. */
    private volatile String lookingGlass;
    /** The last tuning message, sent again to a page that opens later. */
    private String tuningMessage;
    /** The last toggles message (which parts of her are shown), sent again to a page that opens later. */
    private String togglesMessage;
    /** Whether the server is gone: the pages show her frozen and in gray (sent to those that open later too). */
    private String offlineMessage;
    private BiConsumer<Long, String> onEnded = (id, problem) -> {
    };

    public RendererServer() throws IOException {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        secret = HexFormat.of().formatHex(bytes);
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/" + secret + "/", this::handle);
        http.start();
    }

    /** The page's address. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/" + secret + "/");
    }

    /** The page that renders her for a Looking Glass ({@link #lookingGlass}). */
    public URI lookingGlassUrl() {
        return URI.create(url() + "?looking-glass=1");
    }

    /** The calibration and the quilt of the Looking Glass for its page ({@code LookingGlassBridge.Device#renderer}), null for none. */
    public void lookingGlass(JsonObject config) {
        lookingGlass = config == null ? null : GSON.toJson(config);
    }

    /** How the Looking Glass page renders her: sent to the pages open, and to those that open later. */
    public synchronized void tuning(LookingGlassTuning tuning) {
        JsonObject message = message("tuning");
        message.add("tuning", GSON.toJsonTree(tuning));
        tuningMessage = GSON.toJson(message);
        broadcast(message);
    }

    /**
     * Which parts of her model are shown, every toggle of it (her state as the server has it, and as the user switched
     * it here): sent to the pages open, and to those that open later; a page applies it once her model is there.
     */
    public synchronized void toggles(List<BodyToggle> toggles) {
        JsonObject message = message("toggles");
        message.add("toggles", GSON.toJsonTree(toggles == null ? List.of() : toggles));
        togglesMessage = GSON.toJson(message);
        broadcast(message);
    }

    /**
     * A piece of her voice is coming (before it plays): the page reads her mouth from the sound. Not kept for pages that open later.
     *
     * @param number the number the piece plays under ({@link #speechStarted})
     * @param base64 the file of the piece (wav or mp3), Base64
     */
    public void speech(long number, String base64) {
        JsonObject message = message("speech");
        message.addProperty("index", number);
        message.addProperty("data", base64);
        broadcast(message);
    }

    /** The piece of that number plays now: her mouth moves from here on. */
    public void speechStarted(long number) {
        speechStarted(number, System.currentTimeMillis());
    }

    /**
     * The piece of that number plays from {@code at} (wall-clock time, epoch milliseconds): the page puts her mouth on it and not on the
     * moment it reads the message, which a page busy drawing (the Looking Glass's many views) or reading another piece gets to late.
     */
    public void speechStarted(long number, long at) {
        JsonObject message = message("speech-start");
        message.addProperty("index", number);
        message.addProperty("at", at);
        broadcast(message);
    }

    /** Her speech is over or was stopped: her mouth closes. */
    public void speechStopped() {
        broadcast(message("speech-stop"));
    }

    /**
     * How her eyes are as her state says (what she does: asleep, falling asleep, waking up, awake): {@code closed}, or
     * {@code auto} for open and blinking, reached over that many seconds. An animation's own eyes win over this while it plays.
     * Sent to the pages open and to those that open later.
     */
    public synchronized void eyes(String eyes, double seconds) {
        JsonObject message = message("eyes");
        message.addProperty("eyes", eyes);
        message.addProperty("ease", seconds);
        eyesMessage = GSON.toJson(message);
        broadcast(message);
    }

    /**
     * Her mood and how it shows on her face (the server's {@code mood} frame): the page shows the expression softly on her body
     * (an animation's own face and her state win), and the eyes the mood gives her. A mood with no face clears it. Sent to the pages
     * open and to those that open later.
     */
    public synchronized void mood(com.github.enerccio.joi.protocol.node.MoodState mood) {
        JsonObject message = message("mood");
        if (mood != null && mood.mood() != null) {
            message.addProperty("mood", mood.mood());
            if (mood.expression() != null) {
                message.addProperty("expression", mood.expression());
                message.addProperty("weight", mood.weight() == null ? 0.5 : mood.weight());
            }
            if (mood.eyes() != null) {
                message.addProperty("eyes", mood.eyes());
            }
        }
        moodMessage = GSON.toJson(message);
        broadcast(message);
    }

    /** The server is gone ({@code true}) or back: the pages freeze her and show her in gray, or let her go on. */
    public synchronized void offline(boolean offline) {
        JsonObject message = message("offline");
        message.addProperty("offline", offline);
        offlineMessage = GSON.toJson(message);
        broadcast(message);
    }

    /**
     * Called once an animation played with {@link #play} that does not loop is over (on a thread of the server): its
     * id, and null, or why it could not play.
     */
    public void setOnEnded(BiConsumer<Long, String> onEnded) {
        this.onEnded = onEnded;
    }

    /** Shows her body. */
    public synchronized void show(BodyFiles body) {
        this.body = body;
        playMessage = null;
        if (body.model() == null) {
            modelMessage = null;
            broadcast(message("stop"));
            return;
        }
        JsonObject model = message("model");
        model.addProperty("src", "body/model.vrm?" + Long.toHexString(System.nanoTime()));
        modelMessage = GSON.toJson(model);
        broadcast(model);
    }

    /**
     * Plays one of her animations (the uuid of an animation of her {@code .joia}), null for her rest pose. A looping
     * one plays as made and starts again after its last step; one that does not loop plays its steps once each and
     * then {@link #setOnEnded tells}. One her body does not have ends at once.
     *
     * @return its id, as {@link #setOnEnded} tells it
     */
    public synchronized long play(String animation, boolean loop) {
        long id = ++playing;
        playingLoops = loop;
        JoiaMetadata.Animation found = body.animation(animation);
        if (found == null || found.steps() == null || found.steps().isEmpty()) {
            playMessage = null;
            broadcast(message("stop"));
            if (!loop) {
                Thread.ofVirtual().start(() -> finish(id, animation == null ? null : "her body has no animation " + animation));
            }
            return id;
        }
        double seconds = 0;
        Map<String, Integer> clips = new LinkedHashMap<>();
        JsonArray steps = new JsonArray();
        for (JoiaMetadata.Step step : found.steps()) {
            Integer index = clips.computeIfAbsent(step.clip(), clip -> clips.size());
            JsonObject json = GSON.toJsonTree(step).getAsJsonObject();
            json.remove("clip");
            json.addProperty("src", index);
            if (!loop) {
                json.addProperty("loop", false);
            }
            steps.add(json);
            Double length = body.clipLength(step.clip());
            seconds += (length == null ? UNKNOWN_SECONDS : length) / (step.speed() > 0 ? step.speed() : 1);
        }
        JsonObject sequence = new JsonObject();
        sequence.add("steps", steps);
        sequence.addProperty("loop", loop);
        if (found.eyes() != null || found.expression() != null) {
            JsonObject face = new JsonObject();
            if (found.eyes() != null) {
                face.addProperty("eyes", found.eyes());
            }
            if (found.expression() != null) {
                face.addProperty("expression", found.expression());
            }
            sequence.add("face", face);
        }
        JsonArray urls = new JsonArray();
        clips.keySet().forEach(clip -> urls.add("body/clip/" + clip + ".vrma"));
        JsonObject play = message("play");
        play.addProperty("id", id);
        play.add("clips", urls);
        play.add("sequence", sequence);
        playMessage = GSON.toJson(play);
        LOG.log(System.Logger.Level.INFO, "play " + id + " " + found.name() + (loop ? " (loops)" : "") + " to " + pages.size() + " page(s)");
        broadcast(play);
        if (!loop) {
            watchdog.schedule(() -> finish(id, null), (long) ((seconds + GRACE_SECONDS) * 1000), TimeUnit.MILLISECONDS);
        }
        return id;
    }

    @Override
    public void close() {
        http.stop(0);
        watchdog.shutdownNow();
        pages.forEach(page -> page.offer(""));
    }

    // ----------------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath().substring(secret.length() + 2);
            if (path.isEmpty()) {
                path = "index.html";
            }
            boolean get = "GET".equals(exchange.getRequestMethod());
            if (path.equals("events") && get) {
                events(exchange);
            } else if (path.equals("ended") && "POST".equals(exchange.getRequestMethod())) {
                ended(exchange);
            } else if (path.equals("looking-glass.json") && get) {
                json(exchange, lookingGlass);
            } else if (path.equals("body/model.vrm") && get) {
                file(exchange, body.model(), "model/gltf-binary");
            } else if (path.startsWith("body/clip/") && path.endsWith(".vrma") && get) {
                file(exchange, body.clip(path.substring("body/clip/".length(), path.length() - ".vrma".length())), "model/gltf-binary");
            } else if (get && !path.contains("..")) {
                resource(exchange, path);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        synchronized (this) {
            if (modelMessage != null) {
                queue.add(modelMessage);
            }
            if (togglesMessage != null) {
                queue.add(togglesMessage);
            }
            if (playMessage != null) {
                queue.add(playMessage);
            }
            if (offlineMessage != null) {
                queue.add(offlineMessage);
            }
            if (eyesMessage != null) {
                queue.add(eyesMessage);
            }
            if (moodMessage != null) {
                queue.add(moodMessage);
            }
            if (tuningMessage != null) {
                queue.add(tuningMessage);
            }
            pages.add(queue);
        }
        LOG.log(System.Logger.Level.INFO, "a page connected (" + pages.size() + ")");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            while (true) {
                String message = queue.poll(15, TimeUnit.SECONDS);
                if (message != null && message.isEmpty()) {
                    return;
                }
                // a comment keeps the connection open while nothing happens, and finds a page that closed
                out.write((message == null ? ":\n\n" : "data: " + message + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the page closed
        } finally {
            pages.remove(queue);
            LOG.log(System.Logger.Level.INFO, "a page disconnected (" + pages.size() + ")");
        }
    }

    private void ended(HttpExchange exchange) throws IOException {
        JsonObject message;
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes((int) MAX_POST);
            message = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            message = null;
        }
        exchange.sendResponseHeaders(204, -1);
        if (message == null || !message.has("id")) {
            return;
        }
        long id = message.get("id").getAsLong();
        String problem = "error".equals(message.has("type") ? message.get("type").getAsString() : null)
                ? (message.has("message") ? message.get("message").getAsString() : "it could not play") : null;
        finish(id, problem);
    }

    /** An animation ended (a page told, or the watchdog): told once, for what plays now and does not loop. */
    private void finish(long id, String problem) {
        synchronized (this) {
            if (id != playing || playingLoops) {
                return;
            }
            playingLoops = true;
        }
        onEnded.accept(id, problem);
    }

    private void file(HttpExchange exchange, Path file, String type) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    private static void json(HttpExchange exchange, String json) throws IOException {
        if (json == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void resource(HttpExchange exchange, String path) throws IOException {
        try (InputStream in = RendererServer.class.getClassLoader().getResourceAsStream(PAGE + path)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", type(path));
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        }
    }

    private static String type(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static JsonObject message(String type) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        return message;
    }

    private void broadcast(JsonObject message) {
        String text = GSON.toJson(message);
        new ArrayList<>(pages).forEach(page -> page.offer(text));
    }
}
