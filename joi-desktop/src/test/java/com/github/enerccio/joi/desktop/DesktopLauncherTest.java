package com.github.enerccio.joi.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the desktop launcher that need no server: its options, the places it uses and the Jetty base it writes. */
class DesktopLauncherTest {

    @TempDir
    Path dir;

    @Test
    void theDefaultsAreAPortOfOursAndTheLoopback() {
        DesktopLauncher.Options options = DesktopLauncher.Options.parse(new String[0]);
        assertThat(options.port).isEqualTo(8766);
        assertThat(options.portExplicit).isFalse();
        assertThat(options.host).isEqualTo("127.0.0.1");
        assertThat(options.openBrowser).isTrue();
        assertThat(options.controls).isTrue();
        assertThat(options.node).isTrue();
        assertThat(options.data).isNull();
    }

    @Test
    void theOptionsAreRead() {
        DesktopLauncher.Options options = DesktopLauncher.Options.parse(new String[] {"--port", "9000", "--host", "::1", "--data", "/x/joi",
                "--no-browser", "--no-node", "--no-tray", "-psn_0_12345"});
        assertThat(options.port).isEqualTo(9000);
        assertThat(options.portExplicit).isTrue();
        assertThat(options.host).isEqualTo("::1");
        assertThat(options.data).isEqualTo(Path.of("/x/joi"));
        assertThat(options.openBrowser).isFalse();
        assertThat(options.node).isFalse();
        assertThat(options.controls).isFalse();
        assertThat(DesktopLauncher.Options.parse(new String[] {"--help"}).help).isTrue();
    }

    @Test
    void wrongOptionsSayWhatIsWrong() {
        assertThatThrownBy(() -> DesktopLauncher.Options.parse(new String[] {"--port", "x"})).hasMessageContaining("Invalid port");
        assertThatThrownBy(() -> DesktopLauncher.Options.parse(new String[] {"--port", "70000"})).hasMessageContaining("Invalid port");
        assertThatThrownBy(() -> DesktopLauncher.Options.parse(new String[] {"--data"})).hasMessageContaining("Missing value");
        assertThatThrownBy(() -> DesktopLauncher.Options.parse(new String[] {"--nope"})).hasMessageContaining("Unknown option");
    }

    @Test
    void theDataFolderIsTheOptionThenTheSettingThenTheHome() {
        String saved = System.getProperty("joi.data");
        try {
            System.clearProperty("joi.data");
            DesktopLauncher.Options none = DesktopLauncher.Options.parse(new String[0]);
            if (System.getenv("JOI_DATA") == null) {
                assertThat(DesktopLauncher.dataDirectory(none)).isEqualTo(Path.of(System.getProperty("user.home"), ".joi-bundled-client").toAbsolutePath().normalize());
            }
            System.setProperty("joi.data", dir.resolve("set").toString());
            assertThat(DesktopLauncher.dataDirectory(none)).isEqualTo(dir.resolve("set"));
            assertThat(DesktopLauncher.dataDirectory(DesktopLauncher.Options.parse(new String[] {"--data", dir.resolve("flag").toString()})))
                    .isEqualTo(dir.resolve("flag"));
        } finally {
            if (saved == null) {
                System.clearProperty("joi.data");
            } else {
                System.setProperty("joi.data", saved);
            }
        }
    }

    @Test
    void theSocketIsInTheDesktopFolderWhenThePathIsShort() throws Exception {
        Path desktop = Files.createDirectories(dir.resolve("d"));
        assertThat(DesktopLauncher.socketPath(desktop)).isEqualTo(desktop.resolve("api.sock"));
    }

    @Test
    void aTooLongPathMovesTheSocketToAPrivateTemporaryFolder() throws Exception {
        Path desktop = dir;
        while (desktop.toString().getBytes(StandardCharsets.UTF_8).length <= 100) {
            desktop = desktop.resolve("a-folder-name-that-makes-the-path-long");
        }
        Path socket = DesktopLauncher.socketPath(desktop);
        try {
            assertThat(socket.getFileName().toString()).isEqualTo("api.sock");
            assertThat(socket.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(100);
            assertThat(socket.getParent().getFileName().toString()).startsWith("joi-");
            assertThat(DesktopLauncher.socketPath(desktop)).as("the same data folder, the same place").isEqualTo(socket);
            if (Files.getFileStore(socket.getParent()).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(socket.getParent())).isEqualTo(Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            }
        } finally {
            Files.deleteIfExists(socket.getParent());
        }
    }

    @Test
    void theFolderOfTheSocketIsOnlyTheOwners() throws Exception {
        Path folder = dir.resolve("private").resolve("deep");
        DesktopLauncher.createPrivateDirectory(folder);
        if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(folder)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void theJettyBaseHasTheModulesWithTheSocketAndTheWar() throws Exception {
        Path war = dir.resolve("joi.war");
        Path base = DesktopLauncher.prepareJettyBase(dir.resolve("base"), war);
        assertThat(Files.readString(base.resolve("start.d/joi.ini"))).startsWith("--module=server,http,unixdomain-http,ee11-deploy");
        String descriptor = Files.readString(base.resolve("webapps/ROOT.xml"));
        assertThat(descriptor).contains("<Set name=\"war\">" + war.toAbsolutePath() + "</Set>").contains("tempDirectoryPersistent");
        assertThat(base.resolve("environments")).isDirectory();
        // written again on the next start with the same result
        assertThat(DesktopLauncher.prepareJettyBase(dir.resolve("base"), war)).isEqualTo(base);
    }

    @Test
    void aPortIsFreeWhenNothingAnswersOnIt() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
            assertThat(DesktopLauncher.isPortFree("127.0.0.1", port)).isFalse();
        }
        assertThat(DesktopLauncher.isPortFree("127.0.0.1", port)).isTrue();
    }
}
