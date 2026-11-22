package com.github.enerccio.joi.protocol;

import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.node.*;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import com.github.enerccio.joi.protocol.oauth.LocalToken;
import com.github.enerccio.joi.protocol.oauth.OAuthError;
import com.github.enerccio.joi.protocol.oauth.OAuthMetadata;
import com.github.enerccio.joi.protocol.oauth.TokenResponse;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages of the API as the server writes them: the samples that {@link SchemaContractTest} checks against the schemas, and
 * that the Swift generator writes out as the fixtures of the Swift package's tests (a decode and an encode of each).
 */
public final class ProtocolSamples {

    private ProtocolSamples() {
    }

    /** The HTTP API's messages. */
    public static List<Record> http() {
        List<Record> out = new ArrayList<>();
        Usage usage = new Usage(812, 64, 900, 4100, 7300);
        Audio audio = new Audio("audio/mpeg", "SUQz");
        out.add(new ServerInfo("1.0", "joi-server", "0.1.0",
                new AuthInfo(List.of(AuthMethod.TOKEN, AuthMethod.OAUTH),
                        new OAuthEndpoints("http://h/oauth/device_authorization", "http://h/oauth/token", "http://h/?device="))));
        out.add(new ServerInfo("1.0", "joi-server", "0.1.0", new AuthInfo(List.of(AuthMethod.NONE), null)));
        out.add(new Me("peter", "Peter", AuthMethod.NONE));
        out.add(new ChatHistory(List.of(new ChatEntry("m1", "2026-11-18T19:00:00Z", "event", null, "node.first_boot", "desk", "I'm awake."),
                new ChatEntry("m2", "2026-11-18T19:01:00Z", "user", "Hi", null, null, null)), true));
        out.add(new TaskList(List.of(new TaskInfo("t1", "Weather in Prague", "web_search", "Search the web", "waiting", "What is the weather in Prague?", null,
                null, null, "2026-11-20T10:00:00Z", null, null, null))));
        out.add(new TaskInfo("t2", "Read the page", "fetch_page", null, "done", "What does it say?", "https://example.com/", "It says hello.", null,
                "2026-11-20T10:00:00Z", "2026-11-20T10:01:00Z", "2026-11-20T10:02:00Z",
                List.of(new TaskStep("system", "Find it.", null), new TaskStep("assistant", null, "[{\"name\":\"read_page\"}]"),
                        new TaskStep("tool", "Hello", null))));
        out.add(new ChatLog("m3", "2026-11-20T10:00:00Z", "task.auto_approved", "Approved automatically: web_search"));
        out.add(new ChatHistory(List.of(new ChatEntry("m3", "2026-11-20T10:00:00Z", "log", "Approved", "task.approved", null, null)), false));
        out.add(new TaskDenial(null));
        out.add(new TaskDenial("Not now"));
        out.add(new AutoApprovalList(List.of(new AutoApproval("web_search", "web_search", "Search the web"), new AutoApproval("fetch_page", "fetch_page", null))));
        out.add(new AutoApprovalList(List.of()));
        out.add(new TaskApproval(null));
        out.add(new TaskApproval(true));
        out.add(new JoiList(List.of(new JoiSummary("2f0c", "Joi", "2026-11-18T19:00:00Z"), new JoiSummary("88aa", "Kairo", null))));
        out.add(new JoiInfo("2f0c", "Joi", "Peter", new Personality("warm", "Warm"), null, "Hey Joi", null, "Gemma", new Voice("Higgs", "C", "low and warm"),
                new JoiAnimations("p1", null, "p2", List.of("p3", "p4"), List.of())));
        out.add(new JoiInfo("2f0c", "Joi", "Peter", null, "cheerful", "Hey Joi", null, null, null, null));
        Download joim = new Download("http://h/api/v1/models/m1/joim", "a".repeat(64), 120_000_000, "2026-11-19T12:00:00Z", true);
        out.add(new BodyInfo("b1", "Street", new BodyModel("m1", "Ryuu", joim), null,
                List.of(new BodyPart("group:eyes", "eyes", "bare", "bare", true, List.of(new BodyPartState("bare", "bare", true,
                        List.of(new BodyToggle("Band", null, false, false))))), new BodyPart("t1", "halo", null, null, false, null)),
                List.of(new BodyToggle("Band", null, false, true), new BodyToggle("Body", "Hide Halo", true, true)),
                "Silver hair.", new BodyAnimations(false, List.of(new BodyAnimation("a1", "Wave", null, true)),
                new Download("http://h/api/v1/bodies/b1/joia", "b".repeat(64), 5000, "2026-11-19T12:00:00Z", false))));
        out.add(new BodyInfo("b1", null, new BodyModel("m1", null, null), null, List.of(), null, null, new BodyAnimations(true, List.of(), null)));
        out.add(new ChatRequest("Hi!", null, null, null, null));
        out.add(new ChatRequest("Hi!", false, true, true, "kitchen"));
        out.add(new ChatReply("m1", "Hello.", null, "cheerful", "Gemma", audio, null, usage));
        out.add(new ChatReply("m1", "Hello.", "hm", null, null, null, "The speech server is down", usage));
        out.add(new TextDelta("Hel"));
        out.add(new AudioChunk(0, "Hello.", "audio/wav", "UklG"));
        out.add(new Done("m1", null));
        out.add(new ApiError("not_found", "No such assistant"));
        out.add(new DeviceAuthorization("dc", "WDJB-MJHT", "http://h/?device", "http://h/?device=WDJB-MJHT", 600, 5));
        out.add(new TokenResponse("joia_x", "Bearer", 3600, "joir_y"));
        out.add(new LocalToken("joil_x", "Bearer", 3600));
        out.add(new OAuthError("authorization_pending", null));
        out.add(new OAuthMetadata("http://h", "http://h/oauth/device_authorization", "http://h/oauth/token",
                List.of(JoiProtocol.DEVICE_CODE_GRANT, "refresh_token"), List.of("none")));
        return out;
    }

    /** The node WebSocket's messages and frames. */
    public static List<Record> node() {
        List<Record> out = new ArrayList<>();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        JsonObject properties = new JsonObject();
        properties.add("path", path);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", "~/notes.md");

        Hello hello = new Hello(JoiProtocol.NODE_VERSION, "6f1d-4c2a", "desk", "desktop", "joi-node 0.1.0", "2f0c",
                List.of(NodePart.TOOLS, NodePart.AUDIO_OUT, NodePart.APPROVALS), null, null);
        out.add(hello);
        out.add(new Hello("1.3", "n1", "kitchen-pi", null, null, "2f0c", List.of(), "joi_x", true));
        out.add(new Welcome("1.2", "joi-server", "0.1.0", "s1", new JoiSummary("2f0c", "Joi", null), 30));
        out.add(new Welcome("1.5", "joi-server", "0.1.0", "s1", new JoiSummary("2f0c", "Joi", null), 30, "2026-11-20T10:00:00Z"));
        out.add(new Hello("1.5", "n1", "desk", "desktop", null, "2f0c", List.of(), null, true,
                new Resume("2026-11-20T10:00:00Z", "2026-11-20T10:05:00Z", 1006)));
        out.add(new Resume(null, null, null));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("microphone", true);
        Manifest manifest = new Manifest(3, "macos 26.1", "aarch64", capabilities,
                List.of(new ToolDefinition("read_file", "Reads a text file", parameters, ToolTier.ALLOW),
                        new ToolDefinition("shell", "Runs a command", parameters, ToolTier.ASK)));
        out.add(manifest);
        out.add(new Manifest(0, null, null, null, List.of()));
        out.add(new Say("What's on my calendar?", null, null));
        out.add(new Say("Hi", false, true));
        out.add(new Say("Hi", null, null, true));
        out.add(new Stop(null));
        out.add(new Stop("t1"));
        out.add(new Display(true));
        out.add(new PartSwitch("group:eyes", "bare"));
        out.add(new PartKept("group:eyes", "bare", true));
        out.add(new PartRefused("group:eyes", "bare", "Not in front of the guests."));
        out.add(new PartRefused("group:eyes", "bare", null));
        out.add(new PlayAnimation("5b1e0c4e-0000-4000-8000-000000000001", "wave", null));
        out.add(new PlayAnimation("5b1e0c4e-0000-4000-8000-000000000001", "dance", true));
        out.add(new MoodState("happy", "Happy", "happy", 0.7, null));
        out.add(new MoodState("sleepy", "Sleepy", "relaxed", 0.3, "half"));
        out.add(new MoodState(null, null, null, null, null));
        out.add(new ToolUse("desk__read_file", "{\"path\":\"~/notes.md\"}", "milk, eggs", true));
        out.add(new ToolUse("desk__shell", "{}", null, false));
        out.add(new ToolCall("c1", "read_file", arguments, "t1", 60));
        out.add(new ToolCall("c2", "shell", new JsonObject(), null, 60));
        out.add(new ToolProgress("c1", "Reading"));
        out.add(new ToolResult("c1", true, "milk, eggs", null));
        out.add(new ToolResult("c2", false, "The user said no", "denied"));
        out.add(new ToolCancel("c2", null));
        out.add(new Approval("c2", ApprovalState.ASKED, null, null));
        out.add(new Approval("c2", ApprovalState.ALLOWED, "session", "voice"));
        out.add(new ServerEvent("body", "2026-11-19T12:00:00Z"));
        out.add(new TurnStart("s1", "first_boot"));

        out.add(new Frame("hello", null, null, SchemaContractTest.GSON.toJsonTree(hello)));
        out.add(new Frame("say", "t1", null, SchemaContractTest.GSON.toJsonTree(new Say("Hi", null, null))));
        out.add(new Frame("text", null, "t1", SchemaContractTest.GSON.toJsonTree(new TextDelta("Hel"))));
        out.add(new Frame("ping", "p1", null, null));
        out.add(new Frame("pong", null, "p1", null));
        return out;
    }
}
