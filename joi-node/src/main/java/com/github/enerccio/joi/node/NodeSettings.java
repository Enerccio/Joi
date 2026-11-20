package com.github.enerccio.joi.node;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the node remembers between runs, in {@code ~/.joi-client} ({@code JOI_CLIENT} to move it): {@code node.json}
 * (its stable id, its name, the server, the assistant chosen last) and {@code credentials.json} (the token or the
 * OAuth tokens of each server it was connected to, readable by the user alone). The cache of her body's files is {@code cache/}, the harness policy
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
        /**
         * When a change of her parts is shown on her body: {@code kept} (null too) as soon as the server kept it,
         * {@code reaction} when her reaction to it comes, so that the change and her words land together.
         */
        public String partsShown;
        /**
         * What she may do with the notification tool: {@code off} (null too: she does not get the tool), {@code ask} (the user
         * approves each notification) or {@code allow}. The user's choice on this node ({@link Notifications}).
         */
        public String notifications;
    }

    /** {@code credentials.json}: the credentials of each server the node has been connected to, by its address. */
    static final class Store {
        /** Oldest first: the server connected last is the last one. */
        Map<String, Credentials> servers = new LinkedHashMap<>();
    }

    /** The credentials for one server: a token the user made, or the OAuth pair. */
    public static final class Credentials {
        public String token;
        public String accessToken;
        public String refreshToken;
        /** When the access token expires (epoch millis). */
        public long accessExpires;
        /** Whether the node got in with it (or needed none): only such servers are offered again. */
        public boolean connected;

        /** The {@code Authorization} header, null for none. */
        public String authorization() {
            String bearer = token != null ? token : accessToken;
            return bearer == null ? null : "Bearer " + bearer;
        }
    }

    private final Path dir;
    private Node node;
    private Store store;

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

    /** A server address as it is kept: without spaces and the slashes at its end. */
    public static String normalise(String server) {
        return server == null ? "" : server.strip().replaceAll("/+$", "");
    }

    /** The credentials for the server the node is set to ({@link Node#server}); empty ones for a server not seen before. */
    public synchronized Credentials credentials() throws IOException {
        return credentials(node().server);
    }

    /** The credentials for the server; empty ones, kept from the next save, for a server not seen before. */
    public synchronized Credentials credentials(String server) throws IOException {
        return store().servers.computeIfAbsent(normalise(server), _ -> new Credentials());
    }

    public synchronized void saveCredentials() throws IOException {
        write("credentials.json", store(), true);
    }

    /** The servers the node knows, the one connected last first. */
    public synchronized List<String> servers() throws IOException {
        List<String> servers = new ArrayList<>();
        store().servers.forEach((address, credentials) -> {
            if (credentials.connected && !address.isEmpty()) {
                servers.add(address);
            }
        });
        Collections.reverse(servers);
        return servers;
    }

    /** Notes that the node connected to the server: it goes to the front of {@link #servers()}, with what it has. */
    public synchronized void connected(String server) throws IOException {
        String key = normalise(server);
        Credentials kept = store().servers.remove(key);
        kept = kept == null ? new Credentials() : kept;
        kept.connected = true;
        store().servers.put(key, kept);
        saveCredentials();
    }

    /** Forgets the server and its credentials (a token, the pairing); the server itself is not touched. */
    public synchronized void forget(String server) throws IOException {
        store().servers.remove(normalise(server));
        saveCredentials();
    }

    private Store store() throws IOException {
        if (store == null) {
            JsonObject json = read("credentials.json", JsonObject.class);
            if (json == null) {
                store = new Store();
            } else if (json.has("servers")) {
                store = GSON.fromJson(json, Store.class);
                if (store.servers == null) {
                    store.servers = new LinkedHashMap<>();
                }
            } else {
                // the file of the first versions: one set of credentials, which were for the server of the node
                store = new Store();
                Credentials old = GSON.fromJson(json, Credentials.class);
                old.connected = true;
                store.servers.put(normalise(node().server), old);
            }
        }
        return store;
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
