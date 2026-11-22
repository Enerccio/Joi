package com.github.enerccio.joi.node;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What makes a node the one of the server's desktop app. */
class LocalModeTest {

    @Test
    void theUsualNodeHasNoLocalMode() {
        assertThat(LocalMode.parse(new String[0], Map.of())).isNull();
        assertThat(LocalMode.parse(new String[] {"--joi-home-dir", "/x", "http://localhost:8080"}, Map.of())).isNull();
        assertThat(LocalMode.parse(new String[] {"--local", "  "}, Map.of())).isNull();
    }

    @Test
    void theFlagsNameTheSocketAndTheWebAddress() {
        LocalMode mode = LocalMode.parse(new String[] {"--joi-home-dir", "/x", "--local", "/tmp/joi/api.sock", "--web", "http://127.0.0.1:8766"}, Map.of());
        assertThat(mode.socket()).isEqualTo(Path.of("/tmp/joi/api.sock"));
        assertThat(mode.web()).isEqualTo("http://127.0.0.1:8766");
        assertThat(mode.server()).isEqualTo("unix:/tmp/joi/api.sock");
    }

    @Test
    void theEqualsFormWorksAndAnUnknownWebIsNull() {
        LocalMode mode = LocalMode.parse(new String[] {"--local=/tmp/a.sock"}, Map.of());
        assertThat(mode.socket()).isEqualTo(Path.of("/tmp/a.sock"));
        assertThat(mode.web()).isNull();
    }

    @Test
    void theEnvironmentIsTheFallbackAndTheFlagsWin() {
        Map<String, String> env = Map.of("JOI_LOCAL_SOCKET", "/env/api.sock", "JOI_WEB_URL", "http://env:1");
        assertThat(LocalMode.parse(new String[0], env).socket()).isEqualTo(Path.of("/env/api.sock"));
        assertThat(LocalMode.parse(new String[0], env).web()).isEqualTo("http://env:1");
        LocalMode both = LocalMode.parse(new String[] {"--local", "/flag/api.sock", "--web", "http://flag:2"}, env);
        assertThat(both.socket()).isEqualTo(Path.of("/flag/api.sock"));
        assertThat(both.web()).isEqualTo("http://flag:2");
    }

    @Test
    void aRelativeSocketIsMadeAbsolute() {
        assertThat(LocalMode.parse(new String[] {"--local", "api.sock"}, Map.of()).socket()).isAbsolute();
    }
}
