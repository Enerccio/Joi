package com.github.enerccio.joi.node.harness;

import com.github.enerccio.joi.node.NodeTool;
import com.github.enerccio.joi.node.browser.BrowserLink;
import com.github.enerccio.joi.node.NodeTools;
import com.github.enerccio.joi.protocol.node.ToolCall;
import com.github.enerccio.joi.protocol.node.ToolResult;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's harness (DESIGN.md → Node → Harness): her tools for the files and the shell of this machine ({@link FileTools},
 * {@link ShellTool}), under the user's {@link Policy}. Every call goes the same way: the tool plans it (resolves the path or reads
 * the command, and refuses what the policy denies), the user is asked when its tier is ask (unless they allowed the same for this
 * session), the decision is written to the {@link Audit} (a call whose line cannot be written does not run), then it runs and its
 * end is written too.
 */
public final class Harness {

    /** How long the user has to answer, in seconds: no answer is a no. */
    public static final long ANSWER_SECONDS = 120;

    /**
     * What the user is asked about.
     *
     * @param tool    the tool ({@code write_file})
     * @param title   what she wants, for people ("She wants to write a file")
     * @param target  the resolved path, or the command
     * @param detail  more to show (what is written, the folder a command runs in), null for nothing
     * @param warning what the user should know (outside their folders), null for nothing
     */
    public record Question(String tool, String title, String target, String detail, String warning) {
    }

    /** Asks the user; waits for the answer: no, once or for the session; null when they did not answer in time. */
    public interface Asker {
        NodeTools.Scope ask(Question question, long timeoutSeconds) throws InterruptedException;
    }

    /** What a tool makes of a call. */
    sealed interface Plan permits Refused, Go {
    }

    /**
     * The call does not run: the policy denies it or its arguments are wrong.
     *
     * @param decision {@code deny} or {@code bad_arguments}, for the audit
     */
    record Refused(ToolResult result, String decision, JsonObject audit) implements Plan {
    }

    /**
     * The call may run.
     *
     * @param ask        whether the user is asked
     * @param sessionKey what an "allow for session" covers
     * @param audit      what the audit says of it (the target)
     */
    record Go(boolean ask, String sessionKey, Question question, JsonObject audit, Action action) implements Plan {
    }

    /** Runs an allowed call. */
    interface Action {
        Outcome run() throws IOException, InterruptedException;
    }

    /** How a call ended; exit and bytes null when they do not apply. */
    record Outcome(ToolResult result, Integer exit, Long bytes) {
    }

    private final Path nodeHome;
    private final Path userHome;
    private final boolean ignoreCase;
    private final Asker asker;
    private final String via;
    private final Audit audit;
    private final Set<String> session = ConcurrentHashMap.newKeySet();
    private final List<NodeTool> tools;
    private final BrowserLink browser;
    /** The latest turns the user started from this node. */
    private final java.util.LinkedHashSet<String> userTurns = new java.util.LinkedHashSet<>();
    private volatile NodeTools host;

    /**
     * @param nodeHome the node's home: the policy and the audit are there, and nothing under it is hers
     * @param via      how the user answers ({@code click}, {@code keyboard})
     */
    public Harness(Path nodeHome, Asker asker, String via) {
        this(nodeHome, asker, via, null);
    }

    /** With the browser companion: its tools come and go with the extension ({@link BrowserLink}); null for none. */
    public Harness(Path nodeHome, Asker asker, String via, BrowserLink browser) {
        this(nodeHome, Path.of(System.getProperty("user.home")), PathGuard.caseInsensitiveSystem(), asker, via, browser);
    }

    Harness(Path nodeHome, Path userHome, boolean ignoreCase, Asker asker, String via) {
        this(nodeHome, userHome, ignoreCase, asker, via, null);
    }

    Harness(Path nodeHome, Path userHome, boolean ignoreCase, Asker asker, String via, BrowserLink browser) {
        this.nodeHome = nodeHome.toAbsolutePath();
        this.userHome = userHome;
        this.ignoreCase = ignoreCase;
        this.asker = asker;
        this.via = via;
        this.audit = new Audit(this.nodeHome);
        List<NodeTool> all = new ArrayList<>(List.of(new FileTools.ReadFile(this), new FileTools.ListFiles(this), new FileTools.WriteFile(this), new FileTools.ViewImage(this),
                new ShellTool(this)));
        this.browser = browser;
        if (browser != null) {
            all.addAll(BrowserTools.all(this, browser));
            // she gets the browser's tools when the extension connects, and loses them when it goes
            browser.listener(connected -> toolsChanged());
        }
        this.tools = List.copyOf(all);
    }

    /** Gives the server a picture of this machine for her (the image upload of the HTTP API); answers its id. */
    public interface ImageUploader {
        String upload(Path file) throws IOException, InterruptedException;
    }

    private volatile ImageUploader imageUploader;

    /** How pictures reach her: set when the node knows its server and assistant; {@code view_image} is offered only then. */
    public void imageUploader(ImageUploader uploader) {
        this.imageUploader = uploader;
        toolsChanged();
    }

    ImageUploader imageUploader() {
        return imageUploader;
    }

    /** The user's choice for pictures: {@code allow}, {@code folders}, {@code ask} or {@code off}. A new manifest goes out. */
    public synchronized void setImages(String tier) throws IOException {
        if (!List.of("allow", "folders", "ask", "off").contains(tier)) {
            throw new IllegalArgumentException("Pictures are allow, folders, ask or off, not " + tier);
        }
        Policy policy = policy();
        policy.images = "folders".equals(tier) ? null : tier;
        save(policy);
    }

    /** The browser companion's link, null when this node has none. */
    public BrowserLink browser() {
        return browser;
    }

    /**
     * A turn began on this node: one the user started (a {@code say}) lets the tools of tier {@code asked} run without a question.
     *
     * @param cause {@code say} for the user's, else the server's cause
     */
    public void turnStarted(String turn, String cause) {
        if (turn != null && "say".equals(cause)) {
            synchronized (userTurns) {
                userTurns.add(turn);
                while (userTurns.size() > 50) {
                    userTurns.removeFirst();
                }
            }
        }
    }

    /** Whether the user started this turn from this node. */
    boolean userTurn(String turn) {
        if (turn == null) {
            return false;
        }
        synchronized (userTurns) {
            return userTurns.contains(turn);
        }
    }

    /** The user's choice: she may use the browser companion (or not). A new manifest goes out. */
    public synchronized void setBrowser(boolean on) throws IOException {
        Policy policy = policy();
        policy.browser = on ? "on" : "off";
        save(policy);
    }

    /** A browser tool as the user sees it: its name, its tier now and the one it has when the policy names none. */
    public record BrowserTier(String tool, String tier, String defaultTier) {
    }

    /** The browser tools of this node with their tiers, for the settings (empty without the browser companion). */
    public List<BrowserTier> browserTiers() {
        Policy policy = policy();
        List<BrowserTier> list = new ArrayList<>();
        for (NodeTool tool : tools) {
            if (tool instanceof BrowserTools.BrowserTool browserTool) {
                list.add(new BrowserTier(browserTool.name(), browserTool.tier(policy), browserTool.defaultTier()));
            }
        }
        return list;
    }

    /**
     * The user's choice for a browser tool: {@code allow} (never asked), {@code asked} (done at once when the user asked for it in
     * the turn, else asked), {@code ask} (each use asked) or {@code off}. The default tier is kept by leaving the tool out of the policy.
     * A new manifest goes out.
     *
     * @throws IllegalArgumentException for an unknown tool or tier
     */
    public synchronized void setBrowserTier(String tool, String tier) throws IOException {
        BrowserTier current = browserTiers().stream().filter(t -> t.tool().equals(tool)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No browser tool " + tool));
        if (!List.of("allow", "asked", "ask", "off").contains(tier)) {
            throw new IllegalArgumentException("A tier is allow, asked, ask or off, not " + tier);
        }
        Policy policy = policy();
        Map<String, String> tiers = policy.browserTools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy.browserTools);
        if (tier.equals(current.defaultTier())) {
            tiers.remove(tool);
        } else {
            tiers.put(tool, tier);
        }
        policy.browserTools = tiers.isEmpty() ? null : tiers;
        save(policy);
    }

    /** The tools she may use changed (the extension came or went): she gets a new manifest. */
    void toolsChanged() {
        NodeTools to = host;
        if (to != null) {
            to.changed();
        }
    }

    /** The tools, for {@link NodeTools}; each is in the manifest only while the policy has it on. */
    public List<NodeTool> tools() {
        return tools;
    }

    /** The policy as it is on disk now. */
    public Policy policy() {
        return Policy.load(nodeHome);
    }

    public Path policyFile() {
        return nodeHome.resolve(Policy.FILE);
    }

    public Path auditFile() {
        return audit.file();
    }

    /** The checks of the policy as it is now. */
    PathGuard guard(Policy policy) {
        return new PathGuard(userHome, nodeHome, policy, ignoreCase);
    }

    boolean ignoreCase() {
        return ignoreCase;
    }

    /** The user's choice: she may read, list and write files (or not). A new manifest goes out. */
    public synchronized void setFiles(boolean on) throws IOException {
        Policy policy = policy();
        policy.files = on ? "on" : "off";
        save(policy);
    }

    /** The user's choice: she may run commands, each asked about (or not). A new manifest goes out. */
    public synchronized void setShell(boolean on) throws IOException {
        Policy policy = policy();
        policy.shell = on ? "ask" : "off";
        save(policy);
    }

    /** Adds one of the user's folders (as they wrote it; {@code ~} is kept). A new manifest goes out. */
    public synchronized void addRoot(String root) throws IOException {
        if (root == null || root.isBlank()) {
            return;
        }
        Policy policy = policy();
        List<String> roots = new ArrayList<>(policy.roots());
        if (!roots.contains(root.strip())) {
            roots.add(root.strip());
        }
        policy.roots = roots;
        save(policy);
    }

    /** Removes one of the user's folders, as it is written in the policy. A new manifest goes out. */
    public synchronized void removeRoot(String root) throws IOException {
        Policy policy = policy();
        List<String> roots = new ArrayList<>(policy.roots());
        roots.remove(root == null ? null : root.strip());
        policy.roots = roots;
        save(policy);
    }

    private void save(Policy policy) throws IOException {
        policy.save(nodeHome);
        NodeTools to = host;
        if (to != null) {
            to.changed();
        }
    }

    void bind(NodeTools host) {
        this.host = host;
    }

    /** A long call still runs: the server's timeout starts again. */
    void progress(String callId, String message) {
        NodeTools to = host;
        if (to != null) {
            to.progress(callId, message);
        }
    }

    /** Runs a planned call: asks, writes the audit, runs, writes its end. */
    ToolResult execute(ToolCall call, Plan plan) {
        JsonObject line = new JsonObject();
        line.addProperty("call", call.callId());
        line.addProperty("turn", call.turn());
        line.addProperty("tool", call.tool());
        if (plan instanceof Refused refused) {
            refused.audit().entrySet().forEach(e -> line.add(e.getKey(), e.getValue()));
            line.addProperty("decision", refused.decision());
            line.addProperty("ok", false);
            line.addProperty("error", refused.result().error());
            try {
                audit.write(line);
            } catch (IOException e) {
                // nothing runs either way
            }
            return refused.result();
        }
        Go go = (Go) plan;
        go.audit().entrySet().forEach(e -> line.add(e.getKey(), e.getValue()));
        line.addProperty("decision", go.ask() ? "ask" : "allow");
        if (go.ask()) {
            if (session.contains(go.sessionKey())) {
                line.addProperty("answer", "session");
                line.addProperty("via", "earlier");
            } else {
                boolean[] noAnswer = {false};
                NodeTools.Scope scope;
                try {
                    scope = NodeTools.askScoped(host, call.callId(), via, () -> {
                        NodeTools.Scope answer = asker.ask(go.question(), ANSWER_SECONDS);
                        noAnswer[0] = answer == null;
                        return answer;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    line.addProperty("answer", "cancelled");
                    writeQuietly(line);
                    return new ToolResult(call.callId(), false, "Stopped before the user answered.", "cancelled");
                } catch (RuntimeException e) {
                    scope = NodeTools.Scope.DENIED;
                }
                line.addProperty("via", via);
                if (scope == NodeTools.Scope.DENIED) {
                    line.addProperty("answer", noAnswer[0] ? "no_answer" : "denied");
                    line.addProperty("ok", false);
                    line.addProperty("error", "denied");
                    writeQuietly(line);
                    return new ToolResult(call.callId(), false, noAnswer[0] ? "The user did not answer in time, so it was not done."
                            : "The user did not allow it.", "denied");
                }
                if (scope == NodeTools.Scope.SESSION) {
                    session.add(go.sessionKey());
                }
                line.addProperty("answer", scope == NodeTools.Scope.SESSION ? "session" : "once");
            }
        }
        try {
            audit.write(line);
        } catch (IOException e) {
            return new ToolResult(call.callId(), false, "The node could not write its audit log, so nothing was done (" + e.getMessage() + ").", "failed");
        }
        Outcome outcome;
        try {
            outcome = go.action().run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = new Outcome(new ToolResult(call.callId(), false, "Stopped.", "cancelled"), null, null);
        } catch (IOException | RuntimeException e) {
            outcome = new Outcome(new ToolResult(call.callId(), false, String.valueOf(e.getMessage()), "failed"), null, null);
        }
        JsonObject end = new JsonObject();
        end.addProperty("call", call.callId());
        end.addProperty("tool", call.tool());
        end.addProperty("end", true);
        end.addProperty("ok", outcome.result().ok());
        end.addProperty("error", outcome.result().error());
        if (outcome.exit() != null) {
            end.addProperty("exit", outcome.exit());
        }
        if (outcome.bytes() != null) {
            end.addProperty("bytes", outcome.bytes());
        }
        writeQuietly(end);
        return outcome.result();
    }

    private void writeQuietly(JsonObject line) {
        try {
            audit.write(line);
        } catch (IOException e) {
            // the call did not run, or has run: nothing more to stop
        }
    }

    /** Whether "allow for session" covers this already (for tests). */
    boolean allowedForSession(String key) {
        return session.contains(key);
    }
}
