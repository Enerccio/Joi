package com.github.enerccio.joi.node.voice;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The download of the voice models, against a server of this test: no network. */
class VoiceModelsTest {

    @TempDir
    Path home;

    private HttpServer server;
    private final Map<String, byte[]> served = new LinkedHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A {@code .tar.bz2} with the entries: name to content. */
    private static byte[] archive(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BZip2CompressorOutputStream(bytes))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(content.length);
                tar.putArchiveEntry(tarEntry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private record Setup(VoiceModels models, SpeechModel model) {
    }

    private Setup setup(byte[] vad, byte[] archive, String archiveChecksum) throws Exception {
        served.put("/vad.onnx", vad);
        served.put("/model.tar.bz2", archive);
        SpeechModel model = new SpeechModel("test-model", "en", "Test", url("/model.tar.bz2"),
                archiveChecksum == null ? sha256(archive) : archiveChecksum, archive.length, List.of("a.ort", "b.ort", "tokens.txt"));
        VoiceModels models = new VoiceModels(home, new VoiceModels.Asset(url("/vad.onnx"), sha256(vad), vad.length));
        return new Setup(models, model);
    }

    private static final Map<String, String> GOOD = Map.of(
            "folder/", "", "folder/a.ort", "A", "folder/b.ort", "B", "folder/tokens.txt", "T", "folder/README", "not needed");

    @Test
    void installDownloadsTheDetectorAndTheModelAndKeepsOnlyItsFiles() throws Exception {
        Setup setup = setup("vad".getBytes(), archive(GOOD), null);
        VoiceModels models = setup.models();
        assertThat(models.installed(setup.model())).isFalse();
        assertThat(models.missing(setup.model())).isEqualTo(3 + archive(GOOD).length);

        List<Double> progress = new ArrayList<>();
        models.install(setup.model(), progress::add);

        assertThat(models.installed(setup.model())).isTrue();
        assertThat(Files.readString(models.vad())).isEqualTo("vad");
        assertThat(Files.readString(models.dir(setup.model()).resolve("a.ort"))).isEqualTo("A");
        assertThat(Files.readString(models.dir(setup.model()).resolve("tokens.txt"))).isEqualTo("T");
        assertThat(models.dir(setup.model()).resolve("README")).doesNotExist();
        assertThat(progress).isNotEmpty().isSorted().endsWith(1.0);
        assertThat(models.missing(setup.model())).isZero();
        // nothing is left over from the downloads
        try (var files = Files.list(home.resolve("voice"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("silero_vad.onnx", "test-model");
        }
    }

    @Test
    void whatIsThereIsNotDownloadedAgain() throws Exception {
        Setup setup = setup("vad".getBytes(), archive(GOOD), null);
        setup.models().install(setup.model(), p -> {
        });
        int before = requests.get();
        setup.models().install(setup.model(), p -> {
        });
        assertThat(requests).hasValue(before);
    }

    @Test
    void aDownloadWithTheWrongChecksumIsThrownAway() throws Exception {
        Setup setup = setup("vad".getBytes(), archive(GOOD), "0".repeat(64));
        assertThatThrownBy(() -> setup.models().install(setup.model(), p -> {
        })).isInstanceOf(IOException.class).hasMessageContaining("checksum");
        assertThat(setup.models().installed(setup.model())).isFalse();
        assertThat(setup.models().dir(setup.model())).doesNotExist();
        // the detector was fine and stays; no temp file is left
        try (var files = Files.list(home.resolve("voice"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("silero_vad.onnx");
        }
    }

    @Test
    void aDetectorWithTheWrongChecksumIsNotKept() throws Exception {
        served.put("/vad.onnx", "vad".getBytes());
        VoiceModels models = new VoiceModels(home, new VoiceModels.Asset(url("/vad.onnx"), "1".repeat(64), 3));
        assertThatThrownBy(() -> models.install(SpeechModel.MOONSHINE_TINY_EN, p -> {
        })).isInstanceOf(IOException.class);
        assertThat(models.vad()).doesNotExist();
    }

    @Test
    void anAnswerThatIsNotOkFails() throws Exception {
        Setup setup = setup("vad".getBytes(), archive(GOOD), null);
        served.remove("/model.tar.bz2");
        assertThatThrownBy(() -> setup.models().install(setup.model(), p -> {
        })).isInstanceOf(IOException.class).hasMessageContaining("404");
    }

    @Test
    void anArchiveWithoutAFileOfTheModelFails() throws Exception {
        Setup setup = setup("vad".getBytes(), archive(Map.of("folder/a.ort", "A", "folder/tokens.txt", "T")), null);
        assertThatThrownBy(() -> setup.models().install(setup.model(), p -> {
        })).isInstanceOf(IOException.class).hasMessageContaining("b.ort");
        assertThat(setup.models().dir(setup.model())).doesNotExist();
        assertThat(home.resolve("voice").resolve("test-model.part")).doesNotExist();
    }

    @Test
    void theNamesInAnArchiveNeverLeadOutOfTheModelsFolder() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../../outside.txt", "evil");
        entries.put("x/../../a.ort", "A");
        entries.put("/abs/b.ort", "B");
        entries.put("tokens.txt", "T");
        Setup setup = setup("vad".getBytes(), archive(entries), null);
        setup.models().install(setup.model(), p -> {
        });
        // the files are taken by name into the model's folder; nothing is written anywhere else
        assertThat(setup.models().installed(setup.model())).isTrue();
        assertThat(home.getParent().resolve("outside.txt")).doesNotExist();
        assertThat(home.resolve("outside.txt")).doesNotExist();
        assertThat(home.resolve("a.ort")).doesNotExist();
        try (var files = Files.walk(home)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("silero_vad.onnx", "a.ort", "b.ort", "tokens.txt");
        }
    }
}
