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

/**
 * The clipboard tool: she gets it only when the user allowed it, the user sees every text before it is copied, and there is
 * no way to copy without asking.
 */
class ClipboardOffersTest {

    @TempDir
    Path dir;

    private final List<String> copied = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> frames = new ArrayList<>();
    private final List<Manifest> manifests = new ArrayList<>();
    private volatile boolean answer = true;
    private NodeSettings settings;
    private ClipboardOffers offers;
    private NodeTools tools;

    @BeforeEach
    void setUp() {
        settings = new NodeSettings(dir);
        offers = new ClipboardOffers(settings, copied::add, (title, text, seconds) -> {
            asked.add(title + "|" + text);
            return answer;
        });
        tools = new NodeTools(new Notifications(settings, (title, text) -> {
        }, (title, text, seconds) -> true), offers);
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

    private static ToolCall call(String title, String text) {
        JsonObject arguments = new JsonObject();
        if (title != null) {
            arguments.addProperty("title", title);
        }
        if (text != null) {
            arguments.addProperty("text", text);
        }
        return new ToolCall("c1", ClipboardOffers.TOOL, arguments, "t1", 60);
    }

    @Test
    void sheGetsTheToolOnlyWhenTheUserAllowedItAndTheChoiceIsKept() throws Exception {
        assertThat(offers.mode()).isEqualTo(ClipboardOffers.Mode.OFF);
        assertThat(tools.manifest().tools()).isEmpty();

        offers.setMode(ClipboardOffers.Mode.ASK);
        assertThat(manifests).hasSize(1);
        assertThat(manifests.getFirst().tools()).hasSize(1);
        assertThat(manifests.getFirst().tools().getFirst().name()).isEqualTo("offer_to_clipboard");
        assertThat(manifests.getFirst().tools().getFirst().tier()).isEqualTo(ToolTier.ASK);
        assertThat(manifests.getFirst().tools().getFirst().parameters().getAsJsonArray("required").get(0).getAsString()).isEqualTo("text");
        // the same again changes nothing
        offers.setMode(ClipboardOffers.Mode.ASK);
        assertThat(manifests).hasSize(1);

        assertThat(new ClipboardOffers(new NodeSettings(dir), null, null).mode()).isEqualTo(ClipboardOffers.Mode.ASK);
        offers.setMode(ClipboardOffers.Mode.OFF);
        assertThat(manifests.get(1).tools()).isEmpty();
        assertThat(manifests.get(1).version()).isGreaterThan(manifests.get(0).version());
    }

    @Test
    void theToolsOfTheNodeShareOneManifest() throws Exception {
        settings.node().notifications = "allow";
        offers.setMode(ClipboardOffers.Mode.ASK);
        assertThat(manifests.getLast().tools()).extracting(tool -> tool.name()).containsExactly("notify", "offer_to_clipboard");
        assertThat(tools.run(call(null, "ls")).ok()).isTrue();
        assertThat(tools.run(new ToolCall("c2", "reboot", new JsonObject(), "t1", 60)).error()).isEqualTo("unknown_tool");
    }

    @Test
    void inTheModeAllowItIsCopiedAtOnceWithoutAskingAndOnlyThenAndAnUnknownSettingIsOff() throws Exception {
        assertThat(ClipboardOffers.Mode.of("allow")).isEqualTo(ClipboardOffers.Mode.ALLOW);
        assertThat(ClipboardOffers.Mode.of(" ASK ")).isEqualTo(ClipboardOffers.Mode.ASK);
        assertThat(ClipboardOffers.Mode.of("always")).isEqualTo(ClipboardOffers.Mode.OFF);
        assertThat(ClipboardOffers.Mode.of(null)).isEqualTo(ClipboardOffers.Mode.OFF);

        // off: denied, nothing asked or copied
        assertThat(offers.run(call("x", "rm -rf /")).error()).isEqualTo("denied");
        assertThat(asked).isEmpty();
        assertThat(copied).isEmpty();

        offers.setMode(ClipboardOffers.Mode.ALLOW);
        assertThat(manifests.getLast().tools().getFirst().tier()).isEqualTo(ToolTier.ALLOW);
        assertThat(manifests.getLast().tools().getFirst().description()).startsWith("Puts a text on the clipboard");
        answer = false;
        ToolResult result = offers.run(call("x", "ls -la"));
        assertThat(result.ok()).isTrue();
        assertThat(copied).containsExactly("ls -la");
        assertThat(asked).isEmpty();
        assertThat(frames).isEmpty();

        // back to asking: the tier and the description follow
        offers.setMode(ClipboardOffers.Mode.ASK);
        assertThat(manifests.getLast().tools().getFirst().tier()).isEqualTo(ToolTier.ASK);
        assertThat(manifests.getLast().tools().getFirst().description()).startsWith("Offers a text");
    }

    @Test
    void theUserSeesTheTextAndOnlyAYesCopiesIt() throws Exception {
        offers.setMode(ClipboardOffers.Mode.ASK);
        String script = "#!/bin/sh\necho \"hello\"\n\tdone";
        ToolResult yes = offers.run(call("install.sh", script));
        assertThat(yes.ok()).isTrue();
        // the text is as it was written: lines and tabs stay
        assertThat(asked).containsExactly("install.sh|" + script);
        assertThat(copied).containsExactly(script);
        assertThat(frames).containsExactly("c1 ASKED null", "c1 ALLOWED once");

        frames.clear();
        copied.clear();
        answer = false;
        ToolResult no = offers.run(call("again", "other"));
        assertThat(no.ok()).isFalse();
        assertThat(no.error()).isEqualTo("denied");
        assertThat(copied).isEmpty();
        assertThat(frames).containsExactly("c1 ASKED null", "c1 DENIED null");
    }

    @Test
    void theOfferIsCleanedAndBadOnesAreAnswered() throws Exception {
        offers.setMode(ClipboardOffers.Mode.ASK);
        assertThat(offers.run(call("t", null)).error()).isEqualTo("bad_arguments");
        assertThat(offers.run(call("t", "  \n ")).error()).isEqualTo("bad_arguments");
        assertThat(offers.run(call("t", "x".repeat(ClipboardOffers.MAX_TEXT + 1))).error()).isEqualTo("too_long");
        assertThat(asked).isEmpty();

        // the title is one line, NUL is dropped from the text, a missing title is "Joi"
        offers.run(call("a\nb\u0007", "te\u0000xt"));
        offers.run(call(null, "more"));
        assertThat(asked).containsExactly("a b|text", "Joi|more");

        ClipboardOffers broken = new ClipboardOffers(settings, text -> {
            throw new IOException("no clipboard");
        }, (title, text, seconds) -> true);
        ToolResult failed = broken.run(call("t", "text"));
        assertThat(failed.error()).isEqualTo("failed");
        assertThat(failed.content()).contains("no clipboard");
    }

    @Test
    void thePreviewIsCutAndSaysHowLongTheWholeIs() {
        assertThat(ClipboardOffers.preview("short", 100)).isEqualTo("short");
        String shown = ClipboardOffers.preview("x".repeat(250), 100);
        assertThat(shown).startsWith("x".repeat(100) + "…").endsWith("(250 characters in all)");
    }
}
