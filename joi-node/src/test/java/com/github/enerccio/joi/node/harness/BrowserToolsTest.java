package com.github.enerccio.joi.node.harness;

import com.github.enerccio.joi.node.NodeTools;
import com.github.enerccio.joi.node.browser.BrowserLink;
import com.github.enerccio.joi.node.browser.FakeFirefox;
import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser tools under the harness, against a fake Firefox through the real native host: they come and go with the extension
 * and the switch, the tiers (allow, asked in a turn the user started, ask), denied sites, and what she is told.
 */
@DisabledOnOs(OS.WINDOWS)
class BrowserToolsTest {

    @TempDir
    Path temp;

    private final List<Harness.Question> asked = new CopyOnWriteArrayList<>();
    private final List<Manifest> manifests = new CopyOnWriteArrayList<>();
    private final List<String> ops = new CopyOnWriteArrayList<>();
    private final AtomicReference<NodeTools.Scope> answer = new AtomicReference<>(NodeTools.Scope.ONCE);
    private Harness harness;
    private NodeTools tools;
    private BrowserLink link;
    private FakeFirefox firefox;
    private int calls;

    @BeforeEach
    void setUp() throws IOException {
        Path home = Files.createDirectories(temp.resolve("h")).toRealPath();
        link = new BrowserLink(home);
        link.start();
        harness = new Harness(home, home, false, (question, seconds) -> {
            asked.add(question);
            return answer.get();
        }, "test", link);
        tools = new NodeTools(harness.tools());
        tools.attach(new NodeTools.Frames() {
            @Override
            public void approval(String callId, ApprovalState state, String scope, String via) {
            }

            @Override
            public void manifest(Manifest manifest) {
                manifests.add(manifest);
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (firefox != null) {
            firefox.close();
        }
        link.close();
    }

    private static JsonObject tab(int id, String url, String title, boolean audible) {
        JsonObject tab = new JsonObject();
        tab.addProperty("id", id);
        tab.addProperty("url", url);
        tab.addProperty("site", java.net.URI.create(url).getHost());
        tab.addProperty("title", title);
        tab.addProperty("active", id == 1);
        tab.addProperty("audible", audible);
        tab.addProperty("granted", true);
        return tab;
    }

    private void connect() throws Exception {
        JsonObject youtube = tab(1, "https://www.youtube.com/watch?v=x", "Lofi", true);
        JsonObject media = new JsonObject();
        media.addProperty("playing", true);
        media.addProperty("position", 83);
        media.addProperty("duration", 296);
        media.addProperty("volume", 80);
        media.addProperty("speed", 1.0);
        media.addProperty("captions", false);
        youtube.add("media", media);
        JsonObject mail = tab(2, "https://mail.google.com/mail/u/0", "Inbox (3)", false);
        firefox = FakeFirefox.start(link.socket(), (op, args) -> {
            ops.add(op + " " + args);
            JsonObject data = new JsonObject();
            switch (op) {
                case "tab_info" -> {
                    return args.has("tab") && args.get("tab").getAsInt() == 2 ? mail : youtube;
                }
                case "list_tabs", "media_status" -> {
                    JsonArray tabs = new JsonArray();
                    tabs.add(youtube);
                    tabs.add(mail);
                    data.add("tabs", tabs);
                }
                case "media_control" -> {
                    return youtube;
                }
                case "open_url" -> data.addProperty("id", 9);
                default -> {
                }
            }
            return data;
        });
        firefox.awaitConnected(link);
        long until = System.currentTimeMillis() + 5000;
        while (manifests.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
    }

    private ToolResult call(String tool, String json, String turn) {
        return tools.run(new ToolCall("b" + (++calls), tool, JsonParser.parseString(json).getAsJsonObject(), turn, 120));
    }

    @Test
    void theToolsNeedTheSwitchAndTheExtension() throws Exception {
        assertThat(tools.manifest().tools()).isEmpty();
        connect();
        // connected, but the user has not switched it on
        assertThat(tools.manifest().tools()).isEmpty();
        harness.setBrowser(true);
        List<ToolDefinition> offered = tools.manifest().tools();
        assertThat(offered).extracting(ToolDefinition::name).containsExactly("media_status", "media_control", "list_tabs", "focus_tab", "open_url");
        assertThat(offered).extracting(ToolDefinition::tier).containsExactly(ToolTier.ALLOW, ToolTier.ALLOW, ToolTier.ASK, ToolTier.DEPENDS, ToolTier.ASK);
        int before = manifests.size();
        firefox.close();
        long until = System.currentTimeMillis() + 5000;
        while (manifests.size() == before && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        // Firefox went away: a new manifest without them
        assertThat(manifests.getLast().tools()).isEmpty();
    }

    @Test
    void mediaControlRunsAtOnceInATurnTheUserStartedAndIsAskedOtherwise() throws Exception {
        connect();
        harness.setBrowser(true);
        // allowed by default (the site is the extension's grant): asked only when the user chose "When I ask"
        assertThat(call("media_control", "{\"action\":\"play\"}", "an-event").ok()).isTrue();
        assertThat(asked).isEmpty();
        harness.setBrowserTier("media_control", "asked");
        harness.turnStarted("mine", "say");
        ToolResult paused = call("media_control", "{\"action\":\"pause\"}", "mine");
        assertThat(paused.ok()).isTrue();
        assertThat(paused.content()).contains("Tab 1, www.youtube.com");
        assertThat(asked).isEmpty();
        assertThat(ops).anyMatch(op -> op.startsWith("media_control") && op.contains("\"tab\":1") && op.contains("pause"));

        answer.set(NodeTools.Scope.DENIED);
        ToolResult theirs = call("media_control", "{\"action\":\"pause\"}", "an-event");
        assertThat(theirs.error()).isEqualTo("denied");
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst().target()).contains("www.youtube.com").contains("Lofi");
        assertThat(call("media_control", "{\"action\":\"jump\"}", "mine").error()).isEqualTo("bad_arguments");
        assertThat(call("media_control", "{\"action\":\"seek\"}", "mine").error()).isEqualTo("bad_arguments");
    }

    @Test
    void deniedSitesAreNeverWorkedOnNorShown() throws Exception {
        connect();
        harness.setBrowser(true);
        harness.turnStarted("mine", "say");
        assertThat(call("focus_tab", "{\"tab\":2}", "mine").error()).isEqualTo("denied");
        assertThat(call("open_url", "{\"url\":\"https://mail.google.com/\"}", "mine").error()).isEqualTo("denied");
        assertThat(call("open_url", "{\"url\":\"javascript:alert(1)\"}", "mine").error()).isEqualTo("bad_arguments");
        ToolResult list = call("list_tabs", "{}", "mine");
        assertThat(list.ok()).isTrue();
        assertThat(list.content()).contains("Lofi").contains("Tab 2: a site the user's policy keeps from you").doesNotContain("Inbox");
        ToolResult status = call("media_status", "{}", "mine");
        assertThat(status.content()).contains("playing").contains("1:23 of 4:56").contains("volume 80%").doesNotContain("Inbox");
        assertThat(ops).noneMatch(op -> op.startsWith("focus_tab"));
    }

    @Test
    void theTiersFollowThePolicy() throws Exception {
        connect();
        harness.setBrowser(true);
        Policy policy = harness.policy();
        policy.browserTools = new java.util.HashMap<>(java.util.Map.of("open_url", "allow", "list_tabs", "off", "media_status", "nonsense"));
        policy.save(temp.resolve("h"));
        assertThat(tools.manifest().tools()).extracting(ToolDefinition::name).doesNotContain("list_tabs", "media_status");
        ToolResult opened = call("open_url", "{\"url\":\"https://example.com/a\"}", "an-event");
        assertThat(opened.ok()).isTrue();
        assertThat(opened.content()).contains("tab 9");
        assertThat(asked).isEmpty();
        assertThat(Files.readString(harness.auditFile())).contains("\"site\":\"example.com\"").doesNotContain("/a\"");
    }

    @Test
    void allowForSessionCoversTheToolOnEverySite() throws Exception {
        connect();
        harness.setBrowser(true);
        answer.set(NodeTools.Scope.SESSION);
        assertThat(call("open_url", "{\"url\":\"https://example.com/a\"}", "an-event").ok()).isTrue();
        assertThat(asked).hasSize(1);
        answer.set(NodeTools.Scope.DENIED);
        // another site: the extension's grant and the denied sites say where, the node does not ask again
        assertThat(call("open_url", "{\"url\":\"https://news.example.org/\"}", "an-event").ok()).isTrue();
        assertThat(asked).hasSize(1);
        assertThat(Files.readString(harness.auditFile())).contains("\"via\":\"earlier\"");
        // another tool is asked about on its own
        assertThat(call("list_tabs", "{}", "an-event").error()).isEqualTo("denied");
        assertThat(asked).hasSize(2);
        // a denied site stays denied
        assertThat(call("open_url", "{\"url\":\"https://mail.google.com/\"}", "an-event").error()).isEqualTo("denied");
    }

    @Test
    void theUserSetsATierAndTheDefaultIsLeftOutOfThePolicy() throws Exception {
        connect();
        harness.setBrowser(true);
        harness.setBrowserTier("open_url", "allow");
        assertThat(harness.policy().browserTools).containsEntry("open_url", "allow");
        assertThat(harness.browserTiers()).contains(new Harness.BrowserTier("open_url", "allow", "ask"));
        assertThat(call("open_url", "{\"url\":\"https://example.com/\"}", "an-event").ok()).isTrue();
        assertThat(asked).isEmpty();
        harness.setBrowserTier("open_url", "ask");
        assertThat(harness.policy().browserTools).isNull();
        harness.setBrowserTier("list_tabs", "off");
        assertThat(tools.manifest().tools()).extracting(ToolDefinition::name).doesNotContain("list_tabs");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.setBrowserTier("list_tabs", "maybe")).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.setBrowserTier("read_mail", "allow")).isInstanceOf(IllegalArgumentException.class);
    }
}
