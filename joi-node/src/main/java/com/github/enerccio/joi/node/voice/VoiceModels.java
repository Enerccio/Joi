package com.github.enerccio.joi.node.voice;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * The voice models on the disk, in {@code voice/} of the node's home: the voice activity detector ({@code silero_vad.onnx})
 * and the folder of each speech model. Nothing is downloaded unless the user asks for it ({@link #install}), and what is
 * downloaded is checked against a checksum before it is kept. The speech model is a {@code .tar.bz2}: only the files the
 * model lists are taken out of it, by their names.
 */
public final class VoiceModels {

    /** What is downloaded: where from, its checksum and size. */
    public record Asset(URI url, String sha256, long size) {
    }

    /** Silero VAD from the sherpa-onnx project's release of models (about 0.6 MB). */
    public static final Asset SILERO_VAD = new Asset(
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", 643_854L);

    private final Path dir;
    private final Asset vad;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** @param home the node's home ({@link com.github.enerccio.joi.node.NodeSettings#dir}) */
    public VoiceModels(Path home) {
        this(home, SILERO_VAD);
    }

    VoiceModels(Path home, Asset vad) {
        this.dir = home.resolve("voice");
        this.vad = vad;
    }

    /** The file of the voice activity detector. */
    public Path vad() {
        return dir.resolve("silero_vad.onnx");
    }

    /** The folder of a speech model. */
    public Path dir(SpeechModel model) {
        return dir.resolve(model.id());
    }

    /** Whether the detector and the model are there, so that the node can listen. */
    public boolean installed(SpeechModel model) {
        if (!Files.isRegularFile(vad())) {
            return false;
        }
        for (String file : model.files()) {
            if (!Files.isRegularFile(dir(model).resolve(file))) {
                return false;
            }
        }
        return true;
    }

    /** How much is left to download for the model, in bytes (0 when it is all there). */
    public long missing(SpeechModel model) {
        long size = Files.isRegularFile(vad()) ? 0 : vad.size();
        for (String file : model.files()) {
            if (!Files.isRegularFile(dir(model).resolve(file))) {
                return size + model.size();
            }
        }
        return size;
    }

    /**
     * Downloads what is missing: the detector, and the model's archive, whose files are taken out of it. Blocks; call it
     * from a thread of its own. A download that fails its checksum is deleted and fails the call.
     *
     * @param progress told how far it is, 0 to 1
     */
    public void install(SpeechModel model, DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        long total = missing(model);
        long[] done = {0};
        if (!Files.isRegularFile(vad())) {
            Path file = download(vad, done, total, progress);
            Files.move(file, vad(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!hasFiles(model)) {
            Path archive = download(new Asset(model.url(), model.sha256(), model.size()), done, total, progress);
            try {
                unpack(archive, model);
            } finally {
                Files.deleteIfExists(archive);
            }
        }
        progress.accept(1);
    }

    private boolean hasFiles(SpeechModel model) {
        for (String file : model.files()) {
            if (!Files.isRegularFile(dir(model).resolve(file))) {
                return false;
            }
        }
        return true;
    }

    /** Downloads into a temp file in the folder and checks it; the file is deleted when it fails. */
    private Path download(Asset asset, long[] done, long total, DoubleConsumer progress) throws IOException, InterruptedException {
        Path file = Files.createTempFile(dir, "download", ".part");
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(asset.url()).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("The download failed: " + asset.url().getHost() + " answered " + response.statusCode());
            }
            MessageDigest sha = sha256();
            long written = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    written += n;
                    if (asset.size() > 0 && written > asset.size()) {
                        throw new IOException("The download is larger than it should be, it was thrown away.");
                    }
                    out.write(buffer, 0, n);
                    sha.update(buffer, 0, n);
                    done[0] += n;
                    if (total > 0) {
                        progress.accept(Math.min(0.99, done[0] / (double) total));
                    }
                }
            }
            if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(asset.sha256())) {
                throw new IOException("The download is not what it should be (its checksum differs), it was thrown away.");
            }
            return file;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // every Java has it
            throw new IllegalStateException(e);
        }
    }

    /** The model's files out of the archive, into a folder that replaces the model's own once it is whole. */
    private void unpack(Path archive, SpeechModel model) throws IOException {
        Path part = dir.resolve(model.id() + ".part");
        deleteFolder(part);
        Files.createDirectories(part);
        List<String> found = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new BZip2CompressorInputStream(new BufferedInputStream(Files.newInputStream(archive))))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                // only the file's name counts: the entry's folders are never used as a path
                int slash = entry.getName().lastIndexOf('/');
                String name = slash < 0 ? entry.getName() : entry.getName().substring(slash + 1);
                if (model.files().contains(name) && !found.contains(name)) {
                    Files.copy(tar, part.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    found.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteFolder(part);
            throw e;
        }
        if (!found.containsAll(model.files())) {
            deleteFolder(part);
            throw new IOException("The model's archive does not hold " + model.files() + ", found " + found);
        }
        deleteFolder(dir(model));
        Files.move(part, dir(model));
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (var files = Files.walk(folder)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
