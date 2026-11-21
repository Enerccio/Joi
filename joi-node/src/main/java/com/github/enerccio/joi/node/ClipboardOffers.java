package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolDefinition;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.github.enerccio.joi.protocol.node.ToolTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;

/**
 * The node's tool {@code offer_to_clipboard}: she puts a text (a script, a command, what a task of hers wrote) on the clipboard of this
 * machine, or offers it. The choice is the user's, on the node: {@link Mode#OFF} (the default) offers her no tool at all,
 * {@link Mode#ASK} shows the user the text of each offer and copies it only on a yes (no answer in two minutes is a no), and
 * {@link Mode#ALLOW} copies it at once. {@code allow} is a choice to make knowingly: whatever is on the clipboard is pasted into a
 * terminal or a form later, and a text may have come from a web page through her. A change of the mode sends a new manifest.
 */
public final class ClipboardOffers implements NodeTool {

    /** The tool's name in the manifest; she sees it as {@code <node>__offer_to_clipboard}. */
    public static final String TOOL = "offer_to_clipboard";

    /** How long the user has to answer an offer, in seconds: no answer is a no. */
    public static final long ANSWER_SECONDS = 120;

    /** The most characters of one offer (the server's workspace files are no longer). */
    public static final int MAX_TEXT = 100_000;
    private static final int MAX_TITLE = 100;

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
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The clipboard of this machine. */
    public interface Clipboard {
        void copy(String text) throws IOException;
    }

    /** Shows the user the offer; waits for the answer (true: copy it; false: no, or no answer in time). */
    public interface Asker {
        boolean ask(String title, String text, long timeoutSeconds) throws InterruptedException;
    }

    private final NodeSettings settings;
    private final Clipboard clipboard;
    private final Asker asker;
    private volatile NodeTools host;

    public ClipboardOffers(NodeSettings settings, Clipboard clipboard, Asker asker) {
        this.settings = settings;
        this.clipboard = clipboard;
        this.asker = asker;
    }

    @Override
    public String name() {
        return TOOL;
    }

    @Override
    public void bind(NodeTools host) {
        this.host = host;
    }

    @Override
    public ToolDefinition offered() {
        return mode() == Mode.OFF ? null : definition();
    }

    public Mode mode() {
        try {
            return Mode.of(settings.node().clipboard);
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
        settings.node().clipboard = mode.setting();
        settings.saveNode();
        NodeTools to = host;
        if (to != null) {
            to.changed();
        }
    }

    /** The tool as it is offered when it is on. */
    public ToolDefinition definition() {
        JsonObject title = new JsonObject();
        title.addProperty("type", "string");
        title.addProperty("description", "What the text is (a file name, 'install command'), up to " + MAX_TITLE + " characters. Optional.");
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "The text to offer, as it is to be pasted, up to " + MAX_TEXT + " characters.");
        JsonObject properties = new JsonObject();
        properties.add("title", title);
        properties.add("text", text);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("text");
        parameters.add("required", required);
        boolean ask = mode() != Mode.ALLOW;
        return new ToolDefinition(TOOL, (ask ? "Offers a text to the clipboard of this machine: the user sees it and decides whether it is copied"
                : "Puts a text on the clipboard of this machine, so the user can paste it where they want")
                + " (a script, a command, a snippet). Say in the chat what you put there and why. "
                + "It does not put anything in the chat and it is not a way to chat.", parameters, ask ? ToolTier.ASK : ToolTier.ALLOW);
    }

    @Override
    public ToolResult run(ToolCall call) {
        if (!TOOL.equals(call.tool())) {
            return new ToolResult(call.callId(), false, "This node has no tool " + call.tool(), "unknown_tool");
        }
        if (mode() == Mode.OFF) {
            return new ToolResult(call.callId(), false, "The user has not allowed offers to the clipboard on this machine.", "denied");
        }
        String text = string(call.arguments(), "text");
        if (text == null || text.isBlank()) {
            return new ToolResult(call.callId(), false, "Give the text to offer.", "bad_arguments");
        }
        // no NUL: nothing needs it and some programs cut the text there
        String offered = text.replace("\u0000", "");
        if (offered.length() > MAX_TEXT) {
            return new ToolResult(call.callId(), false, "The text is too long (" + offered.length() + " characters, at most " + MAX_TEXT + ").", "too_long");
        }
        String title = string(call.arguments(), "title");
        String shownTitle = title == null || title.isBlank() ? "Joi" : oneLine(title, MAX_TITLE);
        if (mode() == Mode.ASK) {
            boolean allowed;
            try {
                allowed = NodeTools.ask(host, call.callId(), () -> asker.ask(shownTitle, offered, ANSWER_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ToolResult(call.callId(), false, "Stopped.", "cancelled");
            }
            if (!allowed) {
                return new ToolResult(call.callId(), false, "The user did not want this on their clipboard.", "denied");
            }
        }
        try {
            clipboard.copy(offered);
        } catch (IOException | RuntimeException e) {
            return new ToolResult(call.callId(), false, "It could not be copied: " + e.getMessage(), "failed");
        }
        return new ToolResult(call.callId(), true, "Copied to the user's clipboard (" + offered.length() + " characters). Tell them it is there to paste.", null);
    }

    /** A short preview of an offer for a question to the user: its first lines, cut, with how long the whole is. */
    public static String preview(String text, int maxChars) {
        String shown = text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
        return shown + (text.length() > maxChars ? "\n\n(" + text.length() + " characters in all)" : "");
    }

    private static String string(JsonObject arguments, String name) {
        return arguments != null && arguments.has(name) && arguments.get(name).isJsonPrimitive() ? arguments.get(name).getAsString() : null;
    }

    private static String oneLine(String value, int max) {
        String line = value.replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
