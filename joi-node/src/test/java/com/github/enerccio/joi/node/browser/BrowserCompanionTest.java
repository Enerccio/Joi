package com.github.enerccio.joi.node.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The browser companion's plumbing: the native messages' framing, the node's socket and the native host between a fake Firefox and
 * the node (hello, requests and their answers, an error, Firefox going away), and Connect Firefox's files.
 */
class BrowserCompanionTest {

    @TempDir
    Path home;

    private FakeFirefox firefox;
    private BrowserLink link;

    @AfterEach
    void tearDown() {
        if (firefox != null) {
            firefox.close();
        }
        if (link != null) {
            link.close();
        }
    }

    @Test
    void aNativeMessageIsItsLengthAndItsJson() throws IOException {
        byte[] json = "{\"type\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        byte[] framed = NativeHost.frame(json);
        assertThat(framed).hasSize(json.length + 4);
        assertThat(NativeHost.read(new DataInputStream(new ByteArrayInputStream(framed)))).isEqualTo(json);
    }

    @Test
    void aTooLongMessageIsRefused() {
        byte[] framed = NativeHost.frame(new byte[0]);
        // a length far over the limit, in the machine's byte order
        byte[] length = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.nativeOrder()).putInt(Integer.MAX_VALUE).array();
        System.arraycopy(length, 0, framed, 0, 4);
        assertThatThrownBy(() -> NativeHost.read(new DataInputStream(new ByteArrayInputStream(framed)))).isInstanceOf(IOException.class);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void firefoxTalksToTheNodeThroughTheHost() throws Exception {
        List<Boolean> changes = new CopyOnWriteArrayList<>();
        link = new BrowserLink(home);
        link.listener(changes::add);
        link.start();
        firefox = FakeFirefox.start(home.resolve(BrowserLink.SOCKET), (op, args) -> {
            if ("fail".equals(op)) {
                throw new IllegalStateException("No such tab.");
            }
            JsonObject data = new JsonObject();
            data.addProperty("op", op);
            data.add("args", args);
            return data;
        });
        firefox.awaitConnected(link);
        assertThat(link.connected()).isTrue();
        assertThat(link.hello().get("browser").getAsString()).isEqualTo("firefox");
        assertThat(changes).containsExactly(true);
        // the host told the fake Firefox that the node is there
        assertThat(firefox.states()).contains("connected");

        JsonObject args = new JsonObject();
        args.addProperty("tab", 7);
        JsonObject answer = link.request("focus_tab", args, 5000);
        assertThat(answer.get("op").getAsString()).isEqualTo("focus_tab");
        assertThat(answer.getAsJsonObject("args").get("tab").getAsInt()).isEqualTo(7);
        assertThatThrownBy(() -> link.request("fail", new JsonObject(), 5000))
                .isInstanceOf(BrowserLink.BrowserException.class).hasMessageContaining("No such tab");

        firefox.close();
        long until = System.currentTimeMillis() + 5000;
        while (link.connected() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(link.connected()).isFalse();
        assertThat(changes).containsExactly(true, false);
        assertThatThrownBy(() -> link.request("list_tabs", new JsonObject(), 1000)).isInstanceOf(IOException.class);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theHostSaysWhenNoNodeRuns() throws Exception {
        firefox = FakeFirefox.start(home.resolve(BrowserLink.SOCKET), (op, args) -> new JsonObject());
        long until = System.currentTimeMillis() + 5000;
        while (!firefox.states().contains("absent") && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(firefox.states()).containsExactly("absent");
        // the node starts later: the host finds it on its next try
        link = new BrowserLink(home);
        link.start();
        firefox.awaitConnected(link);
        assertThat(firefox.states()).containsExactly("absent", "connected");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void connectFirefoxWritesTheLauncherAndAManifestForTheExtensionOnly() throws IOException {
        Path folder = home.resolve("NativeMessagingHosts");
        BrowserHost.Installed installed = BrowserHost.install(home.resolve("node home"), folder, "Mac OS X", "/opt/java/bin/java",
                List.of("/a/classes", "/b/gson.jar"));
        String script = Files.readString(installed.launcher());
        assertThat(script).startsWith("#!/bin/sh\nexec '/opt/java/bin/java' -cp '/a/classes:/b/gson.jar' " + NativeHost.class.getName())
                .contains("--joi-home-dir '" + home.resolve("node home") + "'");
        assertThat(Files.isExecutable(installed.launcher())).isTrue();
        JsonObject manifest = JsonParser.parseString(Files.readString(installed.manifest())).getAsJsonObject();
        assertThat(manifest.get("name").getAsString()).isEqualTo(BrowserHost.NAME);
        assertThat(manifest.get("path").getAsString()).isEqualTo(installed.launcher().toString());
        assertThat(manifest.getAsJsonArray("allowed_extensions")).hasSize(1);
        assertThat(manifest.getAsJsonArray("allowed_extensions").get(0).getAsString()).isEqualTo(BrowserHost.EXTENSION_ID);
        assertThat(installed.manifest()).isEqualTo(folder.resolve("joi_node.json"));
        assertThat(BrowserHost.manifestFolder("Linux", Path.of("/home/u"), home)).isEqualTo(Path.of("/home/u/.mozilla/native-messaging-hosts"));
    }
}
