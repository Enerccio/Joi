package com.github.enerccio.joi.node.harness;

import com.github.enerccio.joi.node.NodeTools;
import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness: nothing is offered until the user turns it on, reads in the user's folders are silent and everything else is
 * asked, denied paths and the node's home are refused whatever the path looks like ({@code ..}, links), every call is in the
 * audit, a call whose audit cannot be written does not run, and the shell's checks, timeout and cancel.
 */
class HarnessTest {

    @TempDir
    Path temp;

    private Path home;
    private Path nodeHome;
    private Path root;
    private Path outside;
    private final List<Harness.Question> asked = new CopyOnWriteArrayList<>();
    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final List<Manifest> manifests = new CopyOnWriteArrayList<>();
    private final List<String> progress = new CopyOnWriteArrayList<>();
    private final AtomicReference<NodeTools.Scope> answer = new AtomicReference<>(NodeTools.Scope.ONCE);
    private Harness harness;
    private NodeTools tools;
    private int calls;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(temp.resolve("home")).toRealPath();
        nodeHome = Files.createDirectories(home.resolve(".joi-client"));
        root = Files.createDirectories(home.resolve("work"));
        outside = Files.createDirectories(home.resolve("elsewhere"));
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/id_rsa"), "secret");
        harness = new Harness(nodeHome, home, false, (question, seconds) -> {
            asked.add(question);
            return answer.get();
        }, "test");
        tools = new NodeTools(harness.tools());
        tools.attach(new NodeTools.Frames() {
            @Override
            public void approval(String callId, ApprovalState state, String scope, String via) {
                frames.add(callId + " " + state + " " + scope);
            }

            @Override
            public void manifest(Manifest manifest) {
                manifests.add(manifest);
            }

            @Override
            public void progress(String callId, String message) {
                progress.add(callId + " " + message);
            }
        });
    }

    private ToolResult call(String tool, String json) {
        JsonObject arguments = JsonParser.parseString(json).getAsJsonObject();
        return tools.run(new ToolCall("c" + (++calls), tool, arguments, "t1", 120));
    }

    private static String arg(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private void filesOn() throws IOException {
        harness.setFiles(true);
        harness.addRoot(root.toString());
    }

    private List<JsonObject> audit() throws IOException {
        List<JsonObject> lines = new ArrayList<>();
        for (String line : Files.readAllLines(harness.auditFile())) {
            lines.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return lines;
    }

    @Test
    void nothingIsOfferedUntilTheUserTurnsItOn() throws IOException {
        assertThat(tools.manifest().tools()).isEmpty();
        Files.writeString(root.resolve("a.txt"), "hello");
        ToolResult result = call("read_file", "{\"path\":\"" + arg(root.resolve("a.txt")) + "\"}");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("denied");
        assertThat(asked).isEmpty();

        harness.setFiles(true);
        assertThat(manifests).hasSize(1);
        assertThat(manifests.getFirst().tools()).extracting(ToolDefinition::name).containsExactly("read_file", "list_files", "write_file");
        // no folders yet: every read is asked about
        assertThat(manifests.getFirst().tools().getFirst().tier()).isEqualTo(ToolTier.ASK);
        harness.addRoot(root.toString());
        ToolDefinition read = manifests.getLast().tools().getFirst();
        assertThat(read.tier()).isEqualTo(ToolTier.DEPENDS);
        assertThat(read.description()).contains(root.toString());
        harness.setShell(true);
        assertThat(manifests.getLast().tools()).extracting(ToolDefinition::name).contains("run_command");
        assertThat(manifests.getLast().version()).isGreaterThan(manifests.getFirst().version());
    }

    @Test
    void aReadInsideTheFoldersIsSilentAndNumberedAndAudited() throws IOException {
        filesOn();
        Files.writeString(root.resolve("notes.txt"), "one\ntwo\nthree\n");
        ToolResult result = call("read_file", "{\"path\":\"notes.txt\",\"offset\":2,\"limit\":1}");
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).contains("2: two").doesNotContain("1: one").doesNotContain("3: three").contains("more follows (offset 3)");
        assertThat(asked).isEmpty();
        List<JsonObject> audit = audit();
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).get("decision").getAsString()).isEqualTo("allow");
        assertThat(audit.get(0).get("path").getAsString()).isEqualTo(root.resolve("notes.txt").toString());
        assertThat(audit.get(1).get("end").getAsBoolean()).isTrue();
        assertThat(audit.get(1).get("ok").getAsBoolean()).isTrue();
        // never the content
        assertThat(Files.readString(harness.auditFile())).doesNotContain("two");
    }

    @Test
    void aReadElsewhereIsAskedAndADenialIsKept() throws IOException {
        filesOn();
        Files.writeString(outside.resolve("x.txt"), "x");
        answer.set(NodeTools.Scope.DENIED);
        ToolResult denied = call("read_file", "{\"path\":\"" + arg(outside.resolve("x.txt")) + "\"}");
        assertThat(denied.ok()).isFalse();
        assertThat(denied.error()).isEqualTo("denied");
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().warning()).contains("outside your folders");
        assertThat(frames).containsExactly("c1 ASKED null", "c1 DENIED null");

        answer.set(null);
        ToolResult late = call("read_file", "{\"path\":\"" + arg(outside.resolve("x.txt")) + "\"}");
        assertThat(late.content()).contains("did not answer in time");
        assertThat(audit()).extracting(line -> line.has("answer") ? line.get("answer").getAsString() : "")
                .containsExactly("denied", "no_answer");
    }

    @Test
    void allowForSessionCoversTheSameFolderOnly() throws IOException {
        filesOn();
        Files.writeString(outside.resolve("a.txt"), "a");
        Files.writeString(outside.resolve("b.txt"), "b");
        Path other = Files.createDirectories(home.resolve("other"));
        Files.writeString(other.resolve("c.txt"), "c");
        answer.set(NodeTools.Scope.SESSION);
        assertThat(call("read_file", "{\"path\":\"" + arg(outside.resolve("a.txt")) + "\"}").ok()).isTrue();
        assertThat(frames).containsExactly("c1 ASKED null", "c1 ALLOWED session");
        answer.set(NodeTools.Scope.DENIED);
        assertThat(call("read_file", "{\"path\":\"" + arg(outside.resolve("b.txt")) + "\"}").ok()).isTrue();
        assertThat(asked).hasSize(1);
        assertThat(call("read_file", "{\"path\":\"" + arg(other.resolve("c.txt")) + "\"}").ok()).isFalse();
        assertThat(asked).hasSize(2);
    }

    @Test
    void deniedPathsAndTheNodesHomeAreRefusedWithoutAsking() throws IOException {
        harness.setFiles(true);
        harness.addRoot("~");
        assertThat(call("read_file", "{\"path\":\"~/.ssh/id_rsa\"}").error()).isEqualTo("denied");
        assertThat(call("read_file", "{\"path\":\"~/work/../.ssh/id_rsa\"}").error()).isEqualTo("denied");
        assertThat(call("read_file", "{\"path\":\"" + arg(nodeHome.resolve("policy.json")) + "\"}").error()).isEqualTo("denied");
        assertThat(call("write_file", "{\"path\":\"" + arg(nodeHome.resolve("policy.json")) + "\",\"content\":\"{}\"}").error()).isEqualTo("denied");
        assertThat(call("list_files", "{\"path\":\"~/.ssh\"}").error()).isEqualTo("denied");
        assertThat(asked).isEmpty();
        // a list of the home shows the denied entries as such
        ToolResult list = call("list_files", "{\"path\":\"~\"}");
        assertThat(list.content()).contains(".ssh/  (kept from you by the user's policy)").contains("work/");
        assertThat(Policy.load(nodeHome).filesOn()).isTrue();
    }

    @Test
    void caseIsIgnoredWhereTheDiskIgnoresIt() {
        PathGuard guard = new PathGuard(home, nodeHome, Policy.defaults(), true);
        assertThat(guard.denied(home.resolve(".SSH").resolve("id_rsa"))).isTrue();
        assertThat(guard.denied(home.resolve(".sshx"))).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkCannotLeadOutOfAFolder() throws IOException {
        filesOn();
        Files.createSymbolicLink(root.resolve("keys"), home.resolve(".ssh"));
        Files.writeString(outside.resolve("x.txt"), "x");
        Files.createSymbolicLink(root.resolve("away.txt"), outside.resolve("x.txt"));
        assertThat(call("read_file", "{\"path\":\"keys/id_rsa\"}").error()).isEqualTo("denied");
        answer.set(NodeTools.Scope.DENIED);
        assertThat(call("read_file", "{\"path\":\"away.txt\"}").error()).isEqualTo("denied");
        // asked about the real place, outside the folders
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().target()).isEqualTo(outside.resolve("x.txt").toString());
    }

    @Test
    void writesAreAlwaysAskedAndSystemFoldersAreNeverWritten() throws IOException {
        filesOn();
        ToolResult wrote = call("write_file", "{\"path\":\"new/dir/out.txt\",\"content\":\"hi\",\"create_folders\":true}");
        assertThat(wrote.ok()).isTrue();
        assertThat(Files.readString(root.resolve("new/dir/out.txt"))).isEqualTo("hi");
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().detail()).contains("new file").contains("hi");
        assertThat(call("write_file", "{\"path\":\"nofolder/out.txt\",\"content\":\"hi\"}").error()).isEqualTo("bad_arguments");
        PathGuard guard = harness.guard(harness.policy());
        assertThat(guard.writeDenied(guard.resolve("/etc/hosts"))).isTrue();
        assertThat(guard.denied(guard.resolve("/etc/hosts"))).isFalse();
        // the temporary folders are not the system's (on macOS they are under /private)
        assertThat(guard.writeDenied(guard.resolve(System.getProperty("java.io.tmpdir")))).isFalse();
        answer.set(NodeTools.Scope.DENIED);
        assertThat(call("write_file", "{\"path\":\"out.txt\",\"content\":\"no\"}").error()).isEqualTo("denied");
        assertThat(root.resolve("out.txt")).doesNotExist();
    }

    @Test
    void aBinaryFileIsRefusedAndAFolderIsNotAFile() throws IOException {
        filesOn();
        Files.write(root.resolve("b.bin"), new byte[]{1, 0, 2});
        assertThat(call("read_file", "{\"path\":\"b.bin\"}").content()).contains("binary");
        assertThat(call("read_file", "{\"path\":\"" + arg(root) + "\"}").error()).isEqualTo("bad_arguments");
    }

    @Test
    void aCallWhoseAuditCannotBeWrittenDoesNotRun() throws IOException {
        filesOn();
        Files.createDirectories(harness.auditFile());
        ToolResult result = call("write_file", "{\"path\":\"out.txt\",\"content\":\"hi\"}");
        assertThat(result.ok()).isFalse();
        assertThat(result.content()).contains("audit");
        assertThat(root.resolve("out.txt")).doesNotExist();
    }

    @Test
    void aPolicyThatCannotBeReadAllowsNothing() throws IOException {
        filesOn();
        Files.writeString(harness.policyFile(), "{ not json", StandardCharsets.UTF_8);
        assertThat(tools.manifest().tools()).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCommandIsAskedRunAndAudited() throws IOException {
        harness.setShell(true);
        harness.addRoot(root.toString());
        ToolResult result = call("run_command", "{\"command\":\"echo hello; exit 3\"}");
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).startsWith("Exit code 3.").contains("hello");
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().target()).isEqualTo("echo hello; exit 3");
        assertThat(asked.getFirst().detail()).contains(root.toString());
        JsonObject end = audit().getLast();
        assertThat(end.get("exit").getAsInt()).isEqualTo(3);
        assertThat(audit().getFirst().get("command").getAsString()).isEqualTo("echo hello; exit 3");
    }

    @Test
    void deniedProgramsAndPathsInACommandAreRefusedWithoutAsking() throws IOException {
        harness.setShell(true);
        for (String command : List.of("sudo rm -rf /", "ls && /usr/bin/sudo -s", "echo $(doas id)", "cat ~/.ssh/id_rsa",
                "cat $HOME/.ssh/id_rsa", "cat " + home.resolve(".ssh/id_rsa"), "cat ~/.joi-client/credentials.json")) {
            assertThat(call("run_command", "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}").error()).as(command).isEqualTo("denied");
        }
        assertThat(asked).isEmpty();
        PathGuard guard = harness.guard(harness.policy());
        assertThat(ShellTool.deniedPath("cat ~/.dockerignore", guard)).isNull();
        assertThat(ShellTool.deniedPath("cat ~/.docker/config.json", guard)).isNotNull();
        assertThat(ShellTool.deniedProgram("echo pseudo", harness.policy())).isNull();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCommandIsStoppedAtItsTimeout() throws IOException {
        harness.setShell(true);
        ToolResult result = call("run_command", "{\"command\":\"echo started; sleep 30\",\"timeout_seconds\":1}");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("timeout");
        assertThat(result.content()).contains("started");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCancelledCommandIsKilled() throws Exception {
        harness.setShell(true);
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> result.set(call("run_command", "{\"command\":\"sleep 30\"}")));
        long until = System.currentTimeMillis() + 10_000;
        while (audit0().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        thread.interrupt();
        thread.join(10_000);
        assertThat(result.get().error()).isEqualTo("cancelled");
    }

    private List<String> audit0() throws IOException {
        return Files.exists(harness.auditFile()) ? Files.readAllLines(harness.auditFile()) : List.of();
    }

    @Test
    void theOutputKeepsItsStartAndItsEnd() {
        ShellTool.Capture capture = new ShellTool.Capture(5, 5);
        char[] text = "abcdefghijklmnopqrstuvwxyz".toCharArray();
        capture.append(text, text.length);
        assertThat(capture.text()).startsWith("abcde").endsWith("vwxyz").contains("16 characters left out");
        ShellTool.Capture small = new ShellTool.Capture(5, 5);
        small.append("abcdefg".toCharArray(), 7);
        assertThat(small.text()).isEqualTo("abcdefg");
    }
}
