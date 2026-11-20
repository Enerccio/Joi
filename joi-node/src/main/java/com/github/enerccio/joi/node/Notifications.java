package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.ApprovalState;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The node's first tool: {@code notify}, with which she shows the user a notification on this machine (a task of hers
 * finished, something happened and the user may not be looking). The harness is the user's: {@link Mode#OFF} (the
 * default) offers her no tool at all, {@link Mode#ASK} asks the user about each notification ({@code approval} frames), and
 * {@link Mode#ALLOW} lets her. A change of the mode sends a new manifest, so she gets the tool, or loses it, at once.
 */
public final class Notifications {

    /** The tool's name in the manifest; she sees it as {@code <node>__notify}. */
    public static final String TOOL = "notify";

    /** How long the user has to answer an asked notification, in seconds: no answer is a no. */
    public static final long ANSWER_SECONDS = 120;

    private static final int MAX_TITLE = 100;
    private static final int MAX_TEXT = 500;

    /** What she may do with the tool. */
    public enum Mode {
        OFF, ASK, ALLOW;

        /** The setting's value; off for none or one it does not know. */
        public static Mode of(String setting) {
            if (setting != null) {
                for (Mode mode : values()) {
                    if (mode.name().equalsIgnoreCase(setting.strip())) {
                        return mode;
                    }
                }
            }
            return OFF;
        }

        public String setting() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** How a notification is shown on this machine. */
    public interface Screen {
        void show(String title, String text) throws IOException;
    }

    /** Asks the user whether she may show a notification; waits for the answer (true: yes; false: no, or no answer in time). */
    public interface Asker {
        boolean ask(String title, String text, long timeoutSeconds) throws InterruptedException;
    }

    /** Where the node's frames go: the approval of a call, and a new manifest. {@link NodeSocket} is it. */
    public interface Frames {
        void approval(String callId, ApprovalState state, String scope, String via);

        void manifest(Manifest manifest);
    }

    private final NodeSettings settings;
    private final Screen screen;
    private final Asker asker;
    private final AtomicLong version = new AtomicLong(1);
    private volatile Frames frames;

    public Notifications(NodeSettings settings, Screen screen, Asker asker) {
        this.settings = settings;
        this.screen = screen;
        this.asker = asker;
    }

    /** Where a new manifest and the approvals go once connected. */
    public void attach(Frames frames) {
        this.frames = frames;
    }

    public Mode mode() {
        try {
            return Mode.of(settings.node().notifications);
        } catch (IOException e) {
            // the settings cannot be read: she gets nothing
            return Mode.OFF;
        }
    }

    /** The user's choice: kept, and a new manifest sent so that she gets the tool (or loses it) now. */
    public void setMode(Mode mode) throws IOException {
        if (mode == mode()) {
            return;
        }
        settings.node().notifications = mode.setting();
        settings.saveNode();
        Frames to = frames;
        if (to != null) {
            to.manifest(manifest(version.incrementAndGet()));
        }
    }

    /** The manifest for the mode now: the tool when she may use it, no tool when she may not. */
    public Manifest manifest() {
        return manifest(version.get());
    }

    private Manifest manifest(long version) {
        return new Manifest(version, System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("os.arch"), null, mode() == Mode.OFF ? List.of() : List.of(definition()));
    }

    /** The tool as it is offered in the mode now ({@code ask} or {@code allow}). */
    public ToolDefinition definition() {
        JsonObject title = new JsonObject();
        title.addProperty("type", "string");
        title.addProperty("description", "A short title (what it is about), up to " + MAX_TITLE + " characters. Optional.");
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What to tell the user, one or two short sentences, up to " + MAX_TEXT + " characters.");
        JsonObject properties = new JsonObject();
        properties.add("title", title);
        properties.add("text", text);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("text");
        parameters.add("required", required);
        return new ToolDefinition(TOOL, "Shows the user a notification on this machine, so they see it even when they are not "
                + "looking at you: a task you started has finished, or something happened that they should know. Short, and only "
                + "for what matters: it is not a way to chat.", parameters, mode() == Mode.ASK ? ToolTier.ASK : ToolTier.ALLOW);
    }

    /** A call of hers (on a thread of its own: it may wait for the user). */
    public ToolResult run(ToolCall call) {
        if (!TOOL.equals(call.tool())) {
            return new ToolResult(call.callId(), false, "This node has no tool " + call.tool(), "unknown_tool");
        }
        Mode mode = mode();
        if (mode == Mode.OFF) {
            return new ToolResult(call.callId(), false, "The user has not allowed notifications on this machine.", "denied");
        }
        String text = string(call.arguments(), "text");
        if (text == null || text.isBlank()) {
            return new ToolResult(call.callId(), false, "Give the text of the notification.", "bad_arguments");
        }
        String title = string(call.arguments(), "title");
        title = title == null || title.isBlank() ? "Joi" : clean(title, MAX_TITLE);
        text = clean(text, MAX_TEXT);
        if (mode == Mode.ASK) {
            Frames to = frames;
            if (to != null) {
                to.approval(call.callId(), ApprovalState.ASKED, null, null);
            }
            boolean allowed;
            try {
                allowed = asker.ask(title, text, ANSWER_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ToolResult(call.callId(), false, "Stopped.", "cancelled");
            }
            if (to != null) {
                to.approval(call.callId(), allowed ? ApprovalState.ALLOWED : ApprovalState.DENIED, allowed ? "once" : null, "click");
            }
            if (!allowed) {
                return new ToolResult(call.callId(), false, "The user did not allow this notification.", "denied");
            }
        }
        try {
            screen.show(title, text);
        } catch (IOException | RuntimeException e) {
            return new ToolResult(call.callId(), false, "The notification could not be shown: " + e.getMessage(), "failed");
        }
        return new ToolResult(call.callId(), true, "Shown to the user.", null);
    }

    /** A notification of the node's own, for the user to see that it works (the mode is not asked). */
    public void test() throws IOException {
        screen.show("Joi", "This is a test notification.");
    }

    private static String string(JsonObject arguments, String name) {
        return arguments != null && arguments.has(name) && arguments.get(name).isJsonPrimitive() ? arguments.get(name).getAsString() : null;
    }

    /** One line without control characters, cut to the length. */
    private static String clean(String value, int max) {
        String line = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace('\n', ' ').strip();
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
