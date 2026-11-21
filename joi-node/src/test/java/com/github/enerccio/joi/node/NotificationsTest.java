package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The notification tool: she gets it only when the user allowed it, and each use is asked about when the user wants. */
class NotificationsTest {

    @TempDir
    Path dir;

    private final List<String> shown = new ArrayList<>();
    private final List<String> frames = new ArrayList<>();
    private final List<Manifest> manifests = new ArrayList<>();
    private volatile boolean answer = true;
    private NodeSettings settings;
    private Notifications notifications;
    private NodeTools tools;

    @BeforeEach
    void setUp() {
        settings = new NodeSettings(dir);
        notifications = new Notifications(settings, (title, text) -> shown.add(title + "|" + text), (title, text, seconds) -> answer);
        tools = new NodeTools(notifications);
        tools.attach(new NodeTools.Frames() {
            @Override
            public void approval(String callId, ApprovalState state, String scope, String via) {
                frames.add(callId + " " + state + " " + scope);
            }

            @Override
            public void manifest(Manifest manifest) {
                manifests.add(manifest);
            }
        });
    }

    private static ToolCall call(String tool, String title, String text) {
        JsonObject arguments = new JsonObject();
        if (title != null) {
            arguments.addProperty("title", title);
        }
        if (text != null) {
            arguments.addProperty("text", text);
        }
        return new ToolCall("c1", tool, arguments, "t1", 60);
    }

    @Test
    void shePicksUpTheToolOnlyWhenTheUserAllowedItAndTheChoiceIsKept() throws Exception {
        assertThat(notifications.mode()).isEqualTo(Notifications.Mode.OFF);
        assertThat(tools.manifest().tools()).isEmpty();

        notifications.setMode(Notifications.Mode.ASK);
        assertThat(manifests).hasSize(1);
        assertThat(manifests.getFirst().tools()).hasSize(1);
        assertThat(manifests.getFirst().tools().getFirst().name()).isEqualTo("notify");
        assertThat(manifests.getFirst().tools().getFirst().tier()).isEqualTo(ToolTier.ASK);
        assertThat(manifests.getFirst().tools().getFirst().parameters().getAsJsonArray("required").get(0).getAsString()).isEqualTo("text");

        notifications.setMode(Notifications.Mode.ALLOW);
        assertThat(manifests.get(1).tools().getFirst().tier()).isEqualTo(ToolTier.ALLOW);
        assertThat(manifests.get(1).version()).isGreaterThan(manifests.get(0).version());
        // the same again changes nothing
        notifications.setMode(Notifications.Mode.ALLOW);
        assertThat(manifests).hasSize(2);

        notifications.setMode(Notifications.Mode.OFF);
        assertThat(manifests.get(2).tools()).isEmpty();
        notifications.setMode(Notifications.Mode.ALLOW);
        assertThat(new Notifications(new NodeSettings(dir), null, null).mode()).isEqualTo(Notifications.Mode.ALLOW);
    }

    @Test
    void withoutTheUsersAllowanceTheCallIsDeniedAndNothingIsShown() throws Exception {
        ToolResult result = notifications.run(call("notify", "Done", "The search is finished."));
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("denied");
        assertThat(shown).isEmpty();
    }

    @Test
    void whenAllowedItIsShownWithoutAskingAndTheTextIsCleaned() throws Exception {
        notifications.setMode(Notifications.Mode.ALLOW);
        ToolResult result = notifications.run(call("notify", null, "Your task\nis done.\u0007 " + "x".repeat(600)));
        assertThat(result.ok()).isTrue();
        assertThat(frames).isEmpty();
        assertThat(shown).hasSize(1);
        String[] parts = shown.getFirst().split("\\|", 2);
        assertThat(parts[0]).isEqualTo("Joi");
        assertThat(parts[1]).startsWith("Your task is done. ").hasSizeLessThanOrEqualTo(500).endsWith("…").doesNotContain("\n").doesNotContain("\u0007");
    }

    @Test
    void whenAskedTheUserIsAskedFirstAndANoShowsNothing() throws Exception {
        notifications.setMode(Notifications.Mode.ASK);
        ToolResult yes = notifications.run(call("notify", "Done", "Finished."));
        assertThat(yes.ok()).isTrue();
        assertThat(frames).containsExactly("c1 ASKED null", "c1 ALLOWED once");
        assertThat(shown).containsExactly("Done|Finished.");

        frames.clear();
        shown.clear();
        answer = false;
        ToolResult no = notifications.run(call("notify", "Again", "Once more."));
        assertThat(no.ok()).isFalse();
        assertThat(no.error()).isEqualTo("denied");
        assertThat(frames).containsExactly("c1 ASKED null", "c1 DENIED null");
        assertThat(shown).isEmpty();
    }

    @Test
    void badCallsAreAnswered() throws Exception {
        notifications.setMode(Notifications.Mode.ALLOW);
        assertThat(notifications.run(call("notify", "Title", null)).error()).isEqualTo("bad_arguments");
        assertThat(notifications.run(call("notify", "Title", "  ")).error()).isEqualTo("bad_arguments");
        assertThat(notifications.run(call("reboot", null, "now")).error()).isEqualTo("unknown_tool");
        assertThat(shown).isEmpty();

        Notifications broken = new Notifications(settings, (title, text) -> {
            throw new IOException("no notification area");
        }, (title, text, seconds) -> true);
        ToolResult failed = broken.run(call("notify", null, "Hello"));
        assertThat(failed.ok()).isFalse();
        assertThat(failed.error()).isEqualTo("failed");
        assertThat(failed.content()).contains("no notification area");
    }
}
