package com.github.enerccio.joi.node;

import com.github.enerccio.joi.node.browser.BrowserHost;
import com.github.enerccio.joi.node.browser.BrowserLink;
import com.github.enerccio.joi.node.harness.Harness;
import com.github.enerccio.joi.node.harness.Policy;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AutoApproval;
import com.github.enerccio.joi.protocol.http.AutoApprovalList;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.joi.protocol.http.BodyAnimation;
import com.github.enerccio.joi.protocol.http.BodyInfo;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.Download;
import com.github.enerccio.joi.protocol.http.JoiInfo;
import com.github.enerccio.joi.protocol.http.JoiList;
import com.github.enerccio.joi.protocol.http.JoiSummary;
import com.github.enerccio.joi.protocol.http.ServerInfo;
import com.github.enerccio.joi.protocol.http.TaskInfo;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodePart;
import com.github.enerccio.joi.protocol.node.ToolUse;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The node in a terminal: logs in to the server (a token in {@code JOI_TOKEN}, the device grant, or none when the
 * server runs without auth), picks her (the one chosen last, else asks), downloads her body's files into the cache,
 * connects and chats in text. What her body would play is printed ({@link AvatarDirector}); a non-looping animation
 * is taken as played after a moment. Commands: {@code /stop}, {@code /sleep}, {@code /quit}; and for a task of hers
 * that waits for the user's answer ({@code task} frame): {@code /yes}, {@code /always} (the tool from now on, kept by
 * the server) and {@code /no [reason]}, which answer the oldest question; {@code /tasks} lists her tasks, {@code /auto}
 * the tools approved ahead of time, {@code /auto on <tool>} and {@code /auto off <tool>} approve one or take it back;
 * {@code /clipboard off|ask|allow} sets whether she may put text on the clipboard ({@link ClipboardOffers}; with {@code ask} each
 * offer is shown and asked about, {@code /yes} or {@code /no}),
 * {@code /notify off|ask|allow} sets whether she gets the notification tool ({@link Notifications}), {@code /notify test}
 * shows one; the harness ({@link Harness}, the files and the shell of this machine): {@code /files on|off}, {@code /shell ask|off},
 * {@code /roots}, {@code /roots add <path>} and {@code /roots remove <path>}, and a call it asks about is answered with {@code /yes},
 * {@code /session} (the same again until the node quits) or {@code /no}; the browser companion: {@code /browser on|off} and
 * {@code /browser connect} (registers this node as Firefox's native host, {@link BrowserHost}); {@code /image <file> [words]} sends an image (streamed to the server) with the words.
 * <p>
 * {@code java ... JoiNodeCli [http://server:port]}
 */
public final class JoiNodeCli {

    /** How long a non-looping animation "plays" here, without a renderer. */
    private static final long ANIMATION_MS = 1500;

    private final PrintStream out = System.out;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Joi node animations");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) throws Exception {
        new JoiNodeCli().run(args);
    }

    private void run(String[] args) throws Exception {
        NodeSettings settings = NodeSettings.standard(args);
        args = NodeSettings.withoutHome(args);
        NodeSettings.Node node = settings.node();
        if (args.length > 0) {
            node.server = args[0];
        } else if (node.server == null) {
            node.server = "http://localhost:8080";
        }
        node.server = NodeSettings.normalise(node.server);
        node.kind = node.kind == null ? "desktop" : node.kind;
        settings.saveNode();
        ServerClient client = new ServerClient(node.server, settings);

        ServerInfo info = client.info();
        out.println("Joi server " + info.version() + " (protocol " + info.protocol() + ") at " + node.server);
        login(client, settings, info, node.name);
        out.println("Logged in as " + client.me().name());
        settings.connected(node.server);

        JoiSummary chosen = choose(client.jois(), node.joi);
        if (chosen == null) {
            out.println("There is no assistant yet: create one in the server's UI.");
            return;
        }
        node.joi = chosen.id();
        settings.saveNode();
        JoiInfo joi = client.joi(chosen.id());
        Map<String, String> names = load(client, chosen.id());

        ConsoleAvatar avatar = new ConsoleAvatar(names);
        AvatarDirector director = new AvatarDirector(avatar, joi.animations(), new Random());
        avatar.director = director;
        Object lock = director;
        CountDownLatch closed = new CountDownLatch(1);
        TaskQuestions questions = new TaskQuestions();
        // the tools the user switches on (off until they do, with /notify and /clipboard); what they are asked about is answered with /yes or /no
        AtomicReference<CompletableFuture<Boolean>> asking = new AtomicReference<>();
        Notifications notifications = new Notifications(settings, (title, text) -> out.println("[notification] " + title + ": " + text + "\u0007"),
                (title, text, seconds) -> askYesNo(out, asking, "she wants to show you a notification \"" + title + ": " + text + "\"", seconds));
        ClipboardOffers clipboardOffers = new ClipboardOffers(settings, new DesktopClipboard(),
                (title, text, seconds) -> askYesNo(out, asking, "she offers you this for your clipboard (" + title + "):\n"
                        + ClipboardOffers.preview(text, 1500) + "\n", seconds));
        AtomicReference<CompletableFuture<NodeTools.Scope>> harnessAsking = new AtomicReference<>();
        // the browser companion: the extension reaches this node through its native host and this socket
        BrowserLink browserLink = new BrowserLink(settings.dir());
        try {
            browserLink.start();
        } catch (IOException e) {
            out.println("[the browser companion is not available: " + e.getMessage() + "]");
        }
        Harness harness = new Harness(settings.dir(), (question, seconds) -> askHarness(out, harnessAsking, question, seconds), "keyboard", browserLink);
        List<NodeTool> tools = new ArrayList<>(List.of(notifications, clipboardOffers));
        tools.addAll(harness.tools());
        NodeTools nodeTools = new NodeTools(tools);
        NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void turnStarted(String turn, String cause) {
                harness.turnStarted(turn, cause);
                synchronized (lock) {
                    if (!NodeSocket.SAY.equals(cause)) {
                        out.println("[" + cause.replace('_', ' ') + "]");
                    }
                    director.turnStarted(turn, cause, false);
                }
            }

            @Override
            public void text(String turn, String delta) {
                synchronized (lock) {
                    director.text(turn, delta);
                }
            }

            @Override
            public void log(com.github.enerccio.joi.protocol.http.ChatLog note) {
                System.out.println("[note] " + note.text());
            }

            @Override
            public void task(TaskInfo task) {
                TaskInfo asked = questions.take(task);
                if (asked != null) {
                    out.println("[asks] " + asked.tool() + ": \"" + asked.title() + "\""
                            + (asked.toolDescription() == null ? "" : " (" + asked.toolDescription() + ")")
                            + (asked.url() == null ? "" : " " + asked.url()) + " - /yes, /always (this tool from now on) or /no [reason]");
                }
            }

            @Override
            public void animation(com.github.enerccio.joi.protocol.node.PlayAnimation play) {
                synchronized (lock) {
                    director.gesture(play.animation(), play.loops());
                }
            }

            @Override
            public void toolUsed(String turn, ToolUse use) {
                out.println("  (" + use.name() + (use.ok() ? "" : " failed") + ")");
            }

            @Override
            public void reply(String turn, ChatReply reply) {
                synchronized (lock) {
                    director.reply(turn, reply);
                }
            }

            @Override
            public void done(String turn, com.github.enerccio.joi.protocol.http.Done done) {
                synchronized (lock) {
                    director.done(turn);
                }
            }

            @Override
            public void failed(String ref, ApiError error) {
                synchronized (lock) {
                    director.failed(ref, error);
                }
            }

            @Override
            public void disconnected(int code, String reason) {
                synchronized (lock) {
                    out.println("[disconnected: " + code + " " + reason + "; connecting again]");
                    director.disconnected();
                }
            }

            @Override
            public void reconnected(com.github.enerccio.joi.protocol.node.Welcome welcome) {
                out.println("[connected again]");
            }

            @Override
            public void closed(int code, String reason) {
                out.println("[disconnected: " + code + " " + reason + "]");
                closed.countDown();
            }
        });
        Hello hello = new Hello(JoiProtocol.NODE_VERSION, node.nodeId, node.name, node.kind, "joi-node " + version(), chosen.id(),
                List.of(NodePart.TOOLS), null, null);
        Manifest manifest = nodeTools.manifest();
        socket.setTools(nodeTools::run);
        nodeTools.attach(new NodeTools.Frames() {
            @Override
            public void approval(String callId, com.github.enerccio.joi.protocol.node.ApprovalState state, String scope, String via) {
                socket.approval(callId, state, scope, via);
            }

            @Override
            public void manifest(Manifest changed) {
                socket.manifest(changed);
            }

            @Override
            public void progress(String callId, String message) {
                socket.progress(callId, message);
            }
        });
        try {
            socket.localSocket(client.localSocket());
            socket.authorizer(client::authorization);
            socket.connect(client.nodeSocket(), client.authorization(), hello, manifest).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.println("Could not connect: " + e.getMessage());
            return;
        }
        synchronized (lock) {
            director.start();
        }
        out.println("Connected as \"" + node.name + "\". Talk to " + chosen.name() + " (/stop, /sleep, /quit).");
        for (String line; closed.getCount() > 0 && (line = in.readLine()) != null; ) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            switch (line) {
                case "/quit" -> {
                    socket.close();
                    browserLink.close();
                    later.shutdownNow();
                    return;
                }
                case "/stop" -> socket.stop(null);
                case "/yes", "/no", "/session" -> {
                    CompletableFuture<NodeTools.Scope> harnessAsked = harnessAsking.get();
                    CompletableFuture<Boolean> asked = asking.get();
                    if (harnessAsked != null) {
                        harnessAsked.complete(switch (line) {
                            case "/yes" -> NodeTools.Scope.ONCE;
                            case "/session" -> NodeTools.Scope.SESSION;
                            default -> NodeTools.Scope.DENIED;
                        });
                    } else if ("/session".equals(line)) {
                        out.println("[nothing asks now]");
                    } else if (asked != null) {
                        asked.complete("/yes".equals(line));
                    } else {
                        answer(client, chosen.id(), questions, line, null);
                    }
                }
                case "/always" -> answer(client, chosen.id(), questions, line, null);
                case "/notify" -> out.println("[notifications: " + notifications.mode().setting() + "; /notify off, ask, allow or test]");
                case "/notify test" -> {
                    try {
                        notifications.test();
                    } catch (IOException e) {
                        out.println("[the notification could not be shown: " + e.getMessage() + "]");
                    }
                }
                case "/notify off", "/notify ask", "/notify allow" -> {
                    try {
                        notifications.setMode(Notifications.Mode.of(line.substring(8)));
                        out.println("[notifications: " + notifications.mode().setting() + "]");
                    } catch (IOException e) {
                        out.println("[not kept: " + e.getMessage() + "]");
                    }
                }
                case "/clipboard" -> out.println("[clipboard offers: " + clipboardOffers.mode().setting() + "; /clipboard off, ask or allow]");
                case "/clipboard off", "/clipboard ask", "/clipboard allow" -> {
                    try {
                        clipboardOffers.setMode(ClipboardOffers.Mode.of(line.substring(11)));
                        out.println("[clipboard offers: " + clipboardOffers.mode().setting() + "]");
                    } catch (IOException e) {
                        out.println("[not kept: " + e.getMessage() + "]");
                    }
                }
                case "/files", "/shell", "/roots" -> harnessState(harness);
                case "/browser" -> browserState(harness, settings);
                case "/browser on", "/browser off" -> {
                    try {
                        harness.setBrowser(line.endsWith("on"));
                        browserState(harness, settings);
                    } catch (IOException e) {
                        out.println("[not kept: " + e.getMessage() + "]");
                    }
                }
                case "/browser connect" -> {
                    try {
                        BrowserHost.Installed installed = BrowserHost.install(settings.dir());
                        out.println("[Firefox can reach this node now (" + installed.manifest() + "). Install the Joi extension, or restart Firefox if it is installed]");
                    } catch (IOException e) {
                        out.println("[Firefox could not be connected: " + e.getMessage() + "]");
                    }
                }
                case "/files on", "/files off", "/shell ask", "/shell off" -> {
                    try {
                        if (line.startsWith("/files")) {
                            harness.setFiles(line.endsWith("on"));
                        } else {
                            harness.setShell(line.endsWith("ask"));
                        }
                        harnessState(harness);
                    } catch (IOException e) {
                        out.println("[not kept: " + e.getMessage() + "]");
                    }
                }
                case "/tasks" -> tasks(client, chosen.id());
                case "/auto" -> autoApproved(client, chosen.id(), null, null);
                case "/sleep" -> {
                    synchronized (lock) {
                        director.fallAsleep();
                    }
                }
                default -> {
                    if (line.startsWith("/no ")) {
                        answer(client, chosen.id(), questions, "/no", line.substring(4).strip());
                    } else if (line.startsWith("/roots add ") || line.startsWith("/roots remove ")) {
                        try {
                            if (line.startsWith("/roots add ")) {
                                harness.addRoot(line.substring(11).strip());
                            } else {
                                harness.removeRoot(line.substring(14).strip());
                            }
                            harnessState(harness);
                        } catch (IOException e) {
                            out.println("[not kept: " + e.getMessage() + "]");
                        }
                    } else if (line.startsWith("/image ")) {
                        image(client, chosen.id(), socket, line.substring(7).strip());
                    } else if (line.startsWith("/auto ")) {
                        String[] words = line.substring(6).strip().split("\\s+");
                        autoApproved(client, chosen.id(), words[0], words.length > 1 ? words[1] : null);
                    } else {
                        socket.say(line, false, false);
                    }
                }
            }
        }
        socket.close();
        browserLink.close();
        later.shutdownNow();
    }

    /**
     * {@code /image <file> [words]}: sends the image with the words (it is streamed to the server, which keeps it scaled and shows it to her).
     */
    private void image(ServerClient client, String joi, NodeSocket socket, String rest) {
        String[] parts = rest.split("\\s+", 2);
        java.nio.file.Path file = java.nio.file.Path.of(parts[0].replaceFirst("^~", System.getProperty("user.home")));
        if (!java.nio.file.Files.isRegularFile(file)) {
            out.println("[no such file: " + file + "]");
            return;
        }
        try {
            String id = client.uploadImage(joi, file).id();
            socket.say(parts.length > 1 ? parts[1] : "", false, false, false, java.util.List.of(id));
        } catch (ServerClient.ServerException e) {
            out.println("[the image was not sent: " + e.getMessage() + "]");
        } catch (IOException | InterruptedException e) {
            out.println("[the image was not sent: " + e.getMessage() + "]");
        }
    }

    /** The browser companion now. */
    private void browserState(Harness harness, NodeSettings settings) {
        Policy policy = harness.policy();
        out.println("[browser: " + (policy.browserOn() ? "on" : "off") + " (/browser on or off); Firefox "
                + (harness.browser().connected() ? "is connected" : BrowserHost.installed(settings.dir()) ? "is not connected (is the Joi extension installed?)"
                : "cannot reach this node yet (/browser connect)") + "]");
    }

    /** What the harness lets her do now. */
    private void harnessState(Harness harness) {
        Policy policy = harness.policy();
        out.println("[files: " + (policy.filesOn() ? "on" : "off") + " (/files on or off); shell: " + (policy.shellOn() ? "ask" : "off")
                + " (/shell ask or off); your folders: " + (policy.roots().isEmpty() ? "none" : String.join(", ", policy.roots()))
                + " (/roots add or remove <path>); the policy is " + harness.policyFile() + ", the audit " + harness.auditFile() + "]");
    }

    /** Asks the user about a call of the harness on the terminal: /yes, /session or /no; null when no answer came in time. */
    private static NodeTools.Scope askHarness(PrintStream out, AtomicReference<CompletableFuture<NodeTools.Scope>> asking,
                                              Harness.Question question, long seconds) throws InterruptedException {
        CompletableFuture<NodeTools.Scope> answer = new CompletableFuture<>();
        asking.set(answer);
        out.println("[asks] " + question.title() + ": " + question.target()
                + (question.detail() == null ? "" : "\n" + question.detail())
                + (question.warning() == null ? "" : "\n(" + question.warning() + ")")
                + "\n - /yes, /session (the same until the node quits) or /no");
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return null;
        } finally {
            asking.compareAndSet(answer, null);
        }
    }

    /** Asks the user a yes or no on the terminal (answered with /yes or /no); no answer in time is a no. */
    private static boolean askYesNo(PrintStream out, AtomicReference<CompletableFuture<Boolean>> asking, String what, long seconds)
            throws InterruptedException {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        asking.set(answer);
        out.println("[asks] " + what + " - /yes or /no");
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        } finally {
            asking.compareAndSet(answer, null);
        }
    }

    /** Answers the oldest question the server asked about a task of hers, through the HTTP API. */
    private void answer(ServerClient client, String joi, TaskQuestions questions, String command, String reason) {
        TaskInfo asked = questions.oldest();
        if (asked == null) {
            out.println("[nothing is asked]");
            return;
        }
        try {
            if ("/no".equals(command)) {
                client.denyTask(joi, asked.id(), reason == null || reason.isBlank() ? null : reason);
            } else {
                client.approveTask(joi, asked.id(), "/always".equals(command));
            }
        } catch (ServerClient.ServerException e) {
            // answered elsewhere in the meantime: the server's next frame takes it back anyway
            out.println(e.status() == 409 ? "[it was answered already]" : "[not answered: " + e.getMessage() + "]");
        } catch (IOException | InterruptedException e) {
            out.println("[not answered: " + e.getMessage() + "]");
        }
        questions.answered(asked.id());
    }

    /** Her tasks, the newest first. */
    private void tasks(ServerClient client, String joi) {
        try {
            List<TaskInfo> tasks = client.tasks(joi, null).tasks();
            if (tasks.isEmpty()) {
                out.println("[no tasks]");
            }
            for (TaskInfo task : tasks) {
                out.println("[task] " + task.status() + " " + task.tool() + ": " + task.title());
            }
        } catch (IOException | InterruptedException e) {
            out.println("[the tasks could not be read: " + e.getMessage() + "]");
        }
    }

    /** The tools approved ahead of time ({@code on} and {@code tool} null), or one approved ({@code on}) or taken back ({@code off}). */
    private void autoApproved(ServerClient client, String joi, String on, String tool) {
        try {
            AutoApprovalList list;
            if (on == null) {
                list = client.autoApproved(joi);
            } else if (tool != null && ("on".equals(on) || "off".equals(on))) {
                list = client.autoApprove(joi, tool, "on".equals(on));
            } else {
                out.println("[/auto, /auto on <tool> or /auto off <tool>]");
                return;
            }
            if (list.tools().isEmpty()) {
                out.println("[no tool is approved ahead of time]");
            }
            for (AutoApproval approved : list.tools()) {
                out.println("[approved] " + approved.tool() + (approved.description() == null ? "" : " (" + approved.description() + ")")
                        + " - /auto off " + approved.tool() + " takes it back");
            }
        } catch (IOException | InterruptedException e) {
            out.println("[not done: " + e.getMessage() + "]");
        }
    }

    private void login(ServerClient client, NodeSettings settings, ServerInfo info, String nodeName) throws IOException, InterruptedException {
        NodeSettings.Credentials credentials = settings.credentials();
        String env = System.getenv("JOI_TOKEN");
        if (env != null && !env.isBlank()) {
            credentials.token = env.strip();
            settings.saveCredentials();
        }
        if (client.local()) {
            // the server is on this machine: its socket is the proof, no token and no pairing
            client.signInLocal();
            return;
        }
        List<AuthMethod> methods = info.auth().methods();
        if (credentials.authorization() != null || methods.contains(AuthMethod.NONE)) {
            return;
        }
        if (!methods.contains(AuthMethod.OAUTH)) {
            throw new IOException("The server wants a token: make one in User Settings → Access and set JOI_TOKEN");
        }
        DeviceAuthorization codes = client.startDeviceGrant(nodeName);
        out.println("Pair this node: open " + codes.verificationUriComplete() + " (or " + codes.verificationUri()
                + " and enter " + codes.userCode() + ")");
        long deadline = System.currentTimeMillis() + codes.expiresIn() * 1000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.max(1, codes.interval()) * 1000);
            if (client.pollDeviceGrant(codes.deviceCode())) {
                out.println("Paired.");
                return;
            }
        }
        throw new IOException("The code expired before it was approved");
    }

    private JoiSummary choose(JoiList list, String last) throws IOException {
        List<JoiSummary> jois = list.jois();
        if (jois.isEmpty()) {
            return null;
        }
        for (JoiSummary joi : jois) {
            if (joi.id().equals(last)) {
                return joi;
            }
        }
        if (jois.size() == 1) {
            return jois.getFirst();
        }
        for (int i = 0; i < jois.size(); i++) {
            out.println((i + 1) + ") " + jois.get(i).name());
        }
        while (true) {
            out.print("Who? ");
            out.flush();
            String line = in.readLine();
            if (line == null) {
                return null;
            }
            try {
                int index = Integer.parseInt(line.strip()) - 1;
                if (index >= 0 && index < jois.size()) {
                    return jois.get(index);
                }
            } catch (NumberFormatException e) {
                // asked again
            }
        }
    }

    /** Downloads her body's files into the cache (the "loading screen"); returns the names of her animations by id. */
    private Map<String, String> load(ServerClient client, String joi) throws IOException, InterruptedException {
        Map<String, String> names = new HashMap<>();
        BodyInfo body = client.body(joi);
        if (body == null) {
            out.println("She has no body yet: text only.");
            return names;
        }
        out.println("Her body: " + (body.name() == null ? "?" : body.name()));
        if (body.model() != null && body.model().joim() != null) {
            fetch(client, "model", body.model().joim());
        }
        if (body.animations() != null) {
            for (BodyAnimation animation : body.animations().animations()) {
                names.put(animation.id(), animation.name());
            }
            if (body.animations().joia() != null) {
                fetch(client, "animations", body.animations().joia());
            }
        }
        return names;
    }

    private void fetch(ServerClient client, String what, Download file) throws IOException, InterruptedException {
        long[] shown = {-1};
        Path path = client.download(file, bytes -> {
            long percent = file.size() <= 0 ? 100 : bytes * 100 / file.size();
            if (percent / 10 != shown[0]) {
                shown[0] = percent / 10;
                out.print("\r  " + what + ": " + percent + "%");
                out.flush();
            }
        });
        out.println("\r  " + what + ": " + path.getFileName() + (file.current() ? "" : " (not current: written again on the Exit of its dialog)"));
    }

    private static String version() {
        String version = JoiNodeCli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** Her body and voice as lines in the terminal. */
    private final class ConsoleAvatar implements AvatarDirector.Avatar {

        private final Map<String, String> names;
        private AvatarDirector director;

        ConsoleAvatar(Map<String, String> names) {
            this.names = names;
        }

        @Override
        public void play(String animation, boolean loop) {
            if (animation != null) {
                out.println("  <" + names.getOrDefault(animation, animation) + (loop ? ", looping" : "") + ">");
            }
            if (!loop) {
                later.schedule(() -> {
                    AvatarDirector it = director;
                    if (it != null) {
                        synchronized (it) {
                            it.animationEnded();
                        }
                    }
                }, ANIMATION_MS, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void reply(String turn, ChatReply reply) {
            out.println("> " + reply.text());
        }

        @Override
        public void failed(String turn, ApiError error) {
            out.println("[" + error.code() + ": " + error.message() + "]");
        }
    }
}
