package com.github.enerccio.joi.node.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The harness's audit, {@code <home>/audit.jsonl}: one JSON line when a call is decided and one when it ends, appended, never
 * rewritten. What she asked for, the resolved target, the decision and the user's answer, the outcome; never the content of a
 * file or of a command's output. A call whose line cannot be written does not run.
 */
public final class Audit {

    /** The file's name in the node's home. */
    public static final String FILE = "audit.jsonl";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;

    public Audit(Path home) {
        this.file = home.resolve(FILE);
    }

    public Path file() {
        return file;
    }

    /** Appends a line (the time is added). */
    public synchronized void write(JsonObject line) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("at", Instant.now().toString());
        line.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(out) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
}
