package com.github.enerccio.joi.node.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * <b>Connect Firefox</b>: registers the node as the native messaging host of the browser companion. It writes a launcher into the
 * node's home (the Java of this run, its class path, {@link NativeHost}, {@code --joi-home-dir}) and the host's manifest where Firefox
 * looks for it, naming only the extension's id. On Windows the manifest stays in the home and a registry key points to it. The last
 * node home that connected is the one Firefox reaches.
 */
public final class BrowserHost {

    /** The host's name, as the extension calls it. */
    public static final String NAME = "joi_node";
    /** The extension's id (its {@code browser_specific_settings.gecko.id}): the only one allowed to start the host. */
    public static final String EXTENSION_ID = "joi-browser@enerccio.github.io";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What was written. */
    public record Installed(Path launcher, Path manifest) {
    }

    private BrowserHost() {
    }

    /** Where Firefox looks for the manifests of native hosts on this system (the user's own, not the system-wide one). */
    public static Path manifestFolder(String os, Path userHome, Path nodeHome) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return userHome.resolve("Library/Application Support/Mozilla/NativeMessagingHosts");
        }
        if (name.contains("win")) {
            return nodeHome;
        }
        return userHome.resolve(".mozilla/native-messaging-hosts");
    }

    /** Registers this node's home with Firefox, for the Java and the class path of this run. */
    public static Installed install(Path nodeHome) throws IOException {
        String os = System.getProperty("os.name", "");
        Path folder = manifestFolder(os, Path.of(System.getProperty("user.home")), nodeHome);
        Installed installed = install(nodeHome, folder, os, java(), classPath());
        if (os.toLowerCase(Locale.ROOT).contains("win")) {
            register(installed.manifest());
        }
        return installed;
    }

    /** Writes the launcher and the manifest (no registry). */
    static Installed install(Path nodeHome, Path manifestFolder, String os, String java, List<String> classPath) throws IOException {
        Path home = nodeHome.toAbsolutePath();
        Files.createDirectories(home);
        boolean windows = os.toLowerCase(Locale.ROOT).contains("win");
        Path launcher = home.resolve(windows ? "browser-host.bat" : "browser-host.sh");
        String path = String.join(File.pathSeparator, classPath);
        String script;
        if (windows) {
            script = "@echo off\r\n\"" + java + "\" -cp \"" + path + "\" " + NativeHost.class.getName() + " --joi-home-dir \"" + home + "\" %*\r\n";
        } else {
            script = "#!/bin/sh\nexec " + quote(java) + " -cp " + quote(path) + " " + NativeHost.class.getName()
                    + " --joi-home-dir " + quote(home.toString()) + " \"$@\"\n";
        }
        Files.writeString(launcher, script, StandardCharsets.UTF_8);
        if (!windows) {
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("name", NAME);
        manifest.addProperty("description", "Joi node: the browser companion");
        manifest.addProperty("path", launcher.toString());
        manifest.addProperty("type", "stdio");
        JsonArray allowed = new JsonArray();
        allowed.add(EXTENSION_ID);
        manifest.add("allowed_extensions", allowed);
        Files.createDirectories(manifestFolder);
        Path file = manifestFolder.resolve(NAME + ".json");
        Files.writeString(file, GSON.toJson(manifest), StandardCharsets.UTF_8);
        return new Installed(launcher, file);
    }

    /** Whether Firefox would start this node home's host (its manifest is there and names this home's launcher). */
    public static boolean installed(Path nodeHome) {
        String os = System.getProperty("os.name", "");
        Path file = manifestFolder(os, Path.of(System.getProperty("user.home")), nodeHome).resolve(NAME + ".json");
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            JsonObject manifest = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            String path = manifest != null && manifest.has("path") ? manifest.get("path").getAsString() : null;
            return path != null && Path.of(path).startsWith(nodeHome.toAbsolutePath()) && Files.isRegularFile(Path.of(path));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void register(Path manifest) throws IOException {
        Process process = new ProcessBuilder("reg", "add", "HKCU\\Software\\Mozilla\\NativeMessagingHosts\\" + NAME, "/ve", "/t", "REG_SZ",
                "/d", manifest.toString(), "/f").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("The registry key for Firefox could not be written.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped.");
        }
    }

    /** The Java of this run: its {@code bin/java}, else the command that started it. */
    static String java() {
        Path home = Path.of(System.getProperty("java.home"));
        for (String name : List.of("bin/java", "bin/java.exe")) {
            if (Files.isExecutable(home.resolve(name))) {
                return home.resolve(name).toString();
            }
        }
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** The class path the node's classes came from (Maven's exec:java loads them in a class loader of its own). */
    static List<String> classPath() {
        Set<String> entries = new LinkedHashSet<>();
        if (BrowserHost.class.getClassLoader() instanceof URLClassLoader loader) {
            for (URL url : loader.getURLs()) {
                try {
                    entries.add(Path.of(url.toURI()).toString());
                } catch (URISyntaxException | IllegalArgumentException e) {
                    // not a file
                }
            }
        }
        if (entries.isEmpty()) {
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        return new ArrayList<>(entries);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
