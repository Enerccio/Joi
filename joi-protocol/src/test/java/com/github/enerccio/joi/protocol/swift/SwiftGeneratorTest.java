package com.github.enerccio.joi.protocol.swift;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Swift package of the iOS app is written from the schemas, and its generated files are in Git (an Xcode build does not run
 * Maven): a schema changed without a new generation fails here, with the command to run.
 */
class SwiftGeneratorTest {

    /** The module's folder is the working folder of the tests. */
    private static final Path PACKAGE = Path.of("..", "joi-ios", "Packages", "JoiProtocol");

    @Test
    void theGeneratedSwiftIsCurrent() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(PACKAGE.getParent().getParent()), "no joi-ios folder (a build of the server alone)");
        Map<String, String> files = SwiftGenerator.generate();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = PACKAGE.resolve(file.getKey());
            if (!Files.isRegularFile(path) || !Files.readString(path, StandardCharsets.UTF_8).equals(file.getValue())) {
                wrong.add(file.getKey());
            }
        }
        for (String directory : List.of(SwiftGenerator.SOURCES, SwiftGenerator.TESTS)) {
            Path path = PACKAGE.resolve(directory);
            if (Files.isDirectory(path)) {
                try (Stream<Path> existing = Files.list(path)) {
                    existing.map(f -> PACKAGE.relativize(f).toString()).filter(f -> !files.containsKey(f)).forEach(f -> wrong.add(f + " (not generated any more)"));
                }
            }
        }
        assertThat(wrong).as("The Swift package is not what the schemas make. Run: mvn -o -Pswift process-test-classes -pl joi-protocol").isEmpty();
    }

    @Test
    void namesAreSwiftNames() {
        assertThat(SwiftGenerator.camel("device_code")).isEqualTo("deviceCode");
        assertThat(SwiftGenerator.camel("audio-out")).isEqualTo("audioOut");
        assertThat(SwiftGenerator.camel("TOOL_CALL")).isEqualTo("toolCall");
        assertThat(SwiftGenerator.camel("ALLOW")).isEqualTo("allow");
        assertThat(SwiftGenerator.camel("3d")).isEqualTo("_3d");
        assertThat(SwiftGenerator.propertyName("nodeId")).isEqualTo("nodeId");
        assertThat(SwiftGenerator.propertyName("expires_in")).isEqualTo("expiresIn");
        assertThat(SwiftGenerator.caseName("none")).isEqualTo("noneValue");
        assertThat(SwiftGenerator.identifier("protocol")).isEqualTo("`protocol`");
        assertThat(SwiftGenerator.identifier("nodeId")).isEqualTo("nodeId");
    }

    @Test
    void everySchemaIsGenerated() {
        Map<String, String> files = SwiftGenerator.generate();
        assertThat(files).containsKeys(SwiftGenerator.SOURCES + "Hello.swift", SwiftGenerator.SOURCES + "ToolTier.swift",
                SwiftGenerator.SOURCES + "Frames.swift", SwiftGenerator.TESTS + "FixtureTests.swift");
        assertThat(files.get(SwiftGenerator.SOURCES + "Hello.swift")).contains("public var `protocol`: String", "case `protocol` = \"protocol\"".substring(0, 0));
        assertThat(files.get(SwiftGenerator.SOURCES + "ToolTier.swift")).contains("case unknown(String)");
    }
}
