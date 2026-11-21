package com.github.enerccio.joi.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final String HOME = "/home/someone";

    @Test
    void theHomeIsTheDefaultWithNoFlagOrVariable() {
        assertThat(NodeSettings.resolve(new String[0], Map.of(), HOME)).isEqualTo(Path.of(HOME, ".joi-client"));
        assertThat(NodeSettings.resolve(new String[0], Map.of("JOI_HOME_DIR", " ", "JOI_CLIENT", ""), HOME))
                .isEqualTo(Path.of(HOME, ".joi-client"));
    }

    @Test
    void theFlagBeatsTheVariableAndTheVariableBeatsTheOldName() {
        Map<String, String> env = Map.of("JOI_HOME_DIR", "/env/home", "JOI_CLIENT", "/env/client");
        assertThat(NodeSettings.resolve(new String[] {"--joi-home-dir", "/flag/home"}, env, HOME)).isEqualTo(Path.of("/flag/home"));
        assertThat(NodeSettings.resolve(new String[] {"--joi-home-dir=/flag/home"}, env, HOME)).isEqualTo(Path.of("/flag/home"));
        assertThat(NodeSettings.resolve(new String[0], env, HOME)).isEqualTo(Path.of("/env/home"));
        assertThat(NodeSettings.resolve(new String[0], Map.of("JOI_CLIENT", "/env/client"), HOME)).isEqualTo(Path.of("/env/client"));
    }

    @Test
    void aTildeIsTheUsersHomeAndARelativePathIsMadeAbsolute() {
        assertThat(NodeSettings.resolve(new String[] {"--joi-home-dir", "~/.joi-dev"}, Map.of(), HOME)).isEqualTo(Path.of(HOME, ".joi-dev"));
        assertThat(NodeSettings.resolve(new String[] {"--joi-home-dir=~"}, Map.of(), HOME)).isEqualTo(Path.of(HOME));
        assertThat(NodeSettings.resolve(new String[] {"--joi-home-dir", "here/../there"}, Map.of(), HOME))
                .isEqualTo(Path.of("there").toAbsolutePath());
    }

    @Test
    void theFlagNeedsAFolder() {
        assertThatThrownBy(() -> NodeSettings.resolve(new String[] {"--joi-home-dir"}, Map.of(), HOME))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a folder");
        assertThatThrownBy(() -> NodeSettings.resolve(new String[] {"--joi-home-dir="}, Map.of(), HOME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFlagIsTakenOutOfTheArguments() {
        assertThat(NodeSettings.withoutHome("--joi-home-dir", "/x", "http://server:8080")).containsExactly("http://server:8080");
        assertThat(NodeSettings.withoutHome("http://server:8080", "--joi-home-dir=/x")).containsExactly("http://server:8080");
        assertThat(NodeSettings.withoutHome("a", "b")).containsExactly("a", "b");
    }

    @Test
    void twoHomesKeepTheirOwnSettings() throws Exception {
        NodeSettings one = new NodeSettings(dir.resolve("one"));
        NodeSettings two = new NodeSettings(dir.resolve("two"));
        one.node().server = "http://one:8080";
        one.saveNode();
        assertThat(two.node().server).isNull();
        assertThat(two.node().nodeId).isNotEqualTo(one.node().nodeId);
        assertThat(two.cache()).isEqualTo(dir.resolve("two").resolve("cache"));
    }
}
