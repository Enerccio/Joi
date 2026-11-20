package com.github.enerccio.joi.node;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/**
 * What the node remembers between runs, in {@code ~/.joi-client} ({@code JOI_CLIENT} to move it): {@code node.json}
 * (its stable id, its name, the server, the assistant chosen last) and {@code credentials.json} (the token or the
 * OAuth tokens, readable by the user alone). The cache of her body's files is {@code cache/}, the harness policy
 * {@code policy.json} (later).
 */
public final class NodeSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** {@code node.json}. */
    public static final class Node {
        /** Chosen once and kept (a UUID): the server knows the node by it. */
        public String nodeId;
        /** Its short name ({@code desk}): her tools from it are {@code desk__<tool>}. */
        public String name;
        /** What it is: desktop, laptop... */
        public String kind;
        /** The server, {@code http://host:port}. */
        public String server;
        /** The assistant chosen last, null for none. */
        public String joi;
        /**
         * Whether she is shown on the Looking Glass: true to open it as the node starts, false when the user left it or
         * it could not be opened (then it is only tried from the button), null for never chosen.
         */
        public Boolean lookingGlass;
        /** How she is rendered there (the tuning panel), null for the defaults. */
        public com.github.enerccio.joi.node.renderer.LookingGlassTuning lookingGlassTuning;
        /** The Chrome (or Chromium) that shows her there, its executable; null to look where it is usually installed. */
        public String lookingGlassBrowser;
    }

    /** {@code credentials.json}: a token the user made, or the OAuth pair. */
    public static final class Credentials {
        public String token;
        public String accessToken;
        public String refreshToken;
        /** When the access token expires (epoch millis). */
        public long accessExpires;

        /** The {@code Authorization} header, null for none. */
        public String authorization() {
            String bearer = token != null ? token : accessToken;
            return bearer == null ? null : "Bearer " + bearer;
        }
    }

    private final Path dir;
    private Node node;
    private Credentials credentials;

    public NodeSettings(Path dir) {
        this.dir = dir;
    }

    /** {@code JOI_CLIENT}, else {@code ~/.joi-client}. */
    public static NodeSettings standard() {
        String env = System.getenv("JOI_CLIENT");
        return new NodeSettings(env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".joi-client"));
    }

    public Path dir() {
        return dir;
    }

    /** Her body's files, by hash. */
    public Path cache() {
        return dir.resolve("cache");
    }

    /** {@code node.json}, made with a new id and the host's name the first time. */
    public synchronized Node node() throws IOException {
        if (node == null) {
            node = read("node.json", Node.class);
            if (node == null) {
                node = new Node();
            }
            boolean changed = false;
            if (node.nodeId == null) {
                node.nodeId = UUID.randomUUID().toString();
                changed = true;
            }
            if (node.name == null) {
                node.name = defaultName();
                changed = true;
            }
            if (changed) {
                saveNode();
            }
        }
        return node;
    }

    public synchronized void saveNode() throws IOException {
        write("node.json", node, false);
    }

    public synchronized Credentials credentials() throws IOException {
        if (credentials == null) {
            credentials = read("credentials.json", Credentials.class);
            if (credentials == null) {
                credentials = new Credentials();
            }
        }
        return credentials;
    }

    public synchronized void saveCredentials() throws IOException {
        write("credentials.json", credentials, true);
    }

    /** The host's name as a node name: lowercase letters, digits, - and _, at most 32. */
    static String defaultName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "desk";
        }
        String name = host.toLowerCase().replaceAll("\\.local$", "").replaceAll("[^a-z0-9_-]", "-").replaceAll("^[^a-z]+", "");
        name = name.length() > 32 ? name.substring(0, 32) : name;
        return name.isEmpty() ? "desk" : name;
    }

    private <T> T read(String file, Class<T> type) throws IOException {
        Path path = dir.resolve(file);
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (JsonParseException e) {
            throw new IOException(path + " can't be read: " + e.getMessage(), e);
        }
    }

    private void write(String file, Object value, boolean secret) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(file);
        Path temp = Files.createTempFile(dir, file, ".tmp");
        try {
            if (secret) {
                try {
                    Files.setPosixFilePermissions(temp, Set.copyOf(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system: as the user's other files
                }
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
