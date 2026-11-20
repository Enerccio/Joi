package com.github.enerccio.joi.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The credentials the node keeps for each server. */
class NodeSettingsTest {

    @TempDir
    Path dir;

    @Test
    void eachServerHasItsOwnCredentials() throws Exception {
        NodeSettings settings = new NodeSettings(dir);
        settings.node().server = "http://one:8080";
        settings.credentials().token = "t1";
        settings.connected("http://one:8080/");
        settings.node().server = "http://two:8090";
        assertThat(settings.credentials().authorization()).isNull();
        settings.credentials().token = "t2";
        settings.connected("http://two:8090");

        NodeSettings again = new NodeSettings(dir);
        assertThat(again.credentials("http://one:8080").authorization()).isEqualTo("Bearer t1");
        assertThat(again.credentials("http://two:8090/").authorization()).isEqualTo("Bearer t2");
        assertThat(again.servers()).containsExactly("http://two:8090", "http://one:8080");
    }

    @Test
    void aServerNotConnectedIsNotOffered() throws Exception {
        NodeSettings settings = new NodeSettings(dir);
        settings.credentials("http://typo:1").token = "bad";
        settings.saveCredentials();
        assertThat(new NodeSettings(dir).servers()).isEmpty();
    }

    @Test
    void forgettingAServerRemovesItsCredentials() throws Exception {
        NodeSettings settings = new NodeSettings(dir);
        settings.credentials("http://one:8080").token = "t1";
        settings.connected("http://one:8080");
        settings.forget("http://one:8080/");

        NodeSettings again = new NodeSettings(dir);
        assertThat(again.servers()).isEmpty();
        assertThat(again.credentials("http://one:8080").authorization()).isNull();
    }

    @Test
    void theFileOfOlderVersionsBelongsToTheServerOfTheNode() throws Exception {
        Files.writeString(dir.resolve("node.json"), "{\"nodeId\":\"n\",\"name\":\"desk\",\"server\":\"http://old:8080\"}");
        Files.writeString(dir.resolve("credentials.json"), "{\"token\":\"legacy\"}");

        NodeSettings settings = new NodeSettings(dir);
        assertThat(settings.credentials().authorization()).isEqualTo("Bearer legacy");
        assertThat(settings.servers()).containsExactly("http://old:8080");
        settings.node().server = "http://other:8080";
        assertThat(settings.credentials().authorization()).isNull();
    }
}
