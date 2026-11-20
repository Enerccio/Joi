package com.github.enerccio.joi.node.renderer;

import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RendererServerTest {

    private static final String WAVE = "11111111-1111-1111-1111-111111111111";
    private static final String CLIP = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newHttpClient();
    private RendererServer server;
    private final BlockingQueue<String> ended = new LinkedBlockingQueue<>();

    @BeforeEach
    void start() throws Exception {
        Path clip = Files.writeString(temp.resolve("wave.vrma"), "clip bytes");
        JoiaMetadata meta = new JoiaMetadata(
                List.of(new JoiaMetadata.Animation(WAVE, "Wave", null, false, List.of(
                        new JoiaMetadata.Step(CLIP, true, 1, false, false, null, 0),
                        new JoiaMetadata.Step(CLIP, false, 2, true, true, List.of(0d, 0d, 0d, 1d), 0.5)))),
                List.of(new JoiaMetadata.Clip(CLIP, "wave", null, List.of(), false, false, 1d, "wave.vrma", null, null, 1d,
                        List.of(), false, null, null)), null);
        Path joia = temp.resolve("anims.joia");
        JoiaFile.write(joia.toFile(), meta, List.of(clip.toFile()));
        server = new RendererServer();
        server.setOnEnded((id, problem) -> ended.add(id + ":" + problem));
        server.show(BodyFiles.unpack(null, joia, temp.resolve("unpacked")));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void sendsWhatToPlayAndHearsItEnd() throws Exception {
        BlockingQueue<JsonObject> events = events();
        long id = server.play(WAVE, false);
        JsonObject play = events.poll(5, TimeUnit.SECONDS);
        assertThat(play.get("type").getAsString()).isEqualTo("play");
        assertThat(play.get("id").getAsLong()).isEqualTo(id);
        assertThat(play.getAsJsonArray("clips")).hasSize(1);
        assertThat(play.getAsJsonArray("clips").get(0).getAsString()).isEqualTo("body/clip/" + CLIP + ".vrma");
        JsonObject sequence = play.getAsJsonObject("sequence");
        assertThat(sequence.get("loop").getAsBoolean()).isFalse();
        JsonObject second = sequence.getAsJsonArray("steps").get(1).getAsJsonObject();
        assertThat(second.get("src").getAsInt()).isZero();
        assertThat(second.get("reverse").getAsBoolean()).isTrue();
        assertThat(second.get("blend").getAsDouble()).isEqualTo(0.5);
        // played once: the looping step does not loop
        assertThat(sequence.getAsJsonArray("steps").get(0).getAsJsonObject().get("loop").getAsBoolean()).isFalse();

        assertThat(get(server.url().resolve("body/clip/" + CLIP + ".vrma")).body()).isEqualTo("clip bytes");

        post("{\"type\":\"ended\",\"id\":" + id + "}");
        post("{\"type\":\"ended\",\"id\":" + id + "}");
        assertThat(ended.poll(5, TimeUnit.SECONDS)).isEqualTo(id + ":null");
        assertThat(ended.poll(300, TimeUnit.MILLISECONDS)).as("told once").isNull();
    }

    @Test
    void aLoopingOneDoesNotEndAndAStaleEndIsIgnored() throws Exception {
        long first = server.play(WAVE, false);
        long idle = server.play(WAVE, true);
        post("{\"type\":\"ended\",\"id\":" + first + "}");
        post("{\"type\":\"ended\",\"id\":" + idle + "}");
        assertThat(ended.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void endsWithoutAPageAfterItsLengthAndSomeSeconds() throws Exception {
        // the clip is 1 s long; its steps at speeds 1 and 2: 1.5 s, and the grace
        long id = server.play(WAVE, false);
        assertThat(ended.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(ended.poll(6, TimeUnit.SECONDS)).isEqualTo(id + ":null");
    }

    @Test
    void anAnimationHerBodyLacksEndsAtOnce() throws Exception {
        long id = server.play("no-such-animation", false);
        assertThat(ended.poll(5, TimeUnit.SECONDS)).startsWith(id + ":her body has no animation");
    }

    @Test
    void aPageOpenedLaterGetsWhatPlaysNow() throws Exception {
        server.play(WAVE, true);
        BlockingQueue<JsonObject> events = events();
        JsonObject play = events.poll(5, TimeUnit.SECONDS);
        assertThat(play.get("type").getAsString()).isEqualTo("play");
        assertThat(play.getAsJsonObject("sequence").get("loop").getAsBoolean()).isTrue();
    }

    @Test
    void whichPartsAreShownGoesToThePagesOpenAndToOnesOpenedLater() throws Exception {
        BlockingQueue<JsonObject> open = events();
        // let the page connect first
        Thread.sleep(300);
        server.toggles(java.util.List.of(new com.github.enerccio.joi.protocol.http.BodyToggle("Headgear", "Hide Halo", true, false)));
        JsonObject now = open.poll(5, TimeUnit.SECONDS);
        assertThat(now.get("type").getAsString()).isEqualTo("toggles");
        assertThat(now.getAsJsonArray("toggles").get(0).getAsJsonObject().get("shown").getAsBoolean()).isFalse();

        // the last ones are sent again to a page that opens later (the Looking Glass)
        server.toggles(java.util.List.of(new com.github.enerccio.joi.protocol.http.BodyToggle("Headgear", "Hide Halo", true, true)));
        JsonObject later = events().poll(5, TimeUnit.SECONDS);
        assertThat(later.get("type").getAsString()).isEqualTo("toggles");
        assertThat(later.getAsJsonArray("toggles").get(0).getAsJsonObject().get("shown").getAsBoolean()).isTrue();
    }

    @Test
    void thatTheServerIsGoneGoesToThePagesOpenAndToOnesOpenedLater() throws Exception {
        BlockingQueue<JsonObject> open = events();
        Thread.sleep(300);
        server.offline(true);
        JsonObject now = open.poll(5, TimeUnit.SECONDS);
        assertThat(now.get("type").getAsString()).isEqualTo("offline");
        assertThat(now.get("offline").getAsBoolean()).isTrue();

        // a page that opens later (the Looking Glass) is frozen and gray at once, until the server is back
        assertThat(events().poll(5, TimeUnit.SECONDS).get("offline").getAsBoolean()).isTrue();
        server.offline(false);
        assertThat(events().poll(5, TimeUnit.SECONDS).get("offline").getAsBoolean()).isFalse();
    }

    @Test
    void onlyUnderItsSecretPath() throws Exception {
        URI url = server.url();
        URI root = URI.create("http://127.0.0.1:" + url.getPort() + "/events");
        assertThat(get(root).statusCode()).isEqualTo(404);
        assertThat(get(url.resolve("body/model.vrm")).statusCode()).as("no model").isEqualTo(404);
    }

    private BlockingQueue<JsonObject> events() throws Exception {
        BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
        HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(server.url().resolve("events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.startsWith("data: ")) {
                        events.add(JsonParser.parseString(line.substring(6)).getAsJsonObject());
                    }
                }
            } catch (Exception e) {
                // closed
            }
        });
        return events;
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void post(String body) throws Exception {
        http.send(HttpRequest.newBuilder(server.url().resolve("ended")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
