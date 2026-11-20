package com.github.enerccio.joi.node;

import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.joi.protocol.http.BodyAnimation;
import com.github.enerccio.joi.protocol.http.BodyInfo;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.Download;
import com.github.enerccio.joi.protocol.http.JoiInfo;
import com.github.enerccio.joi.protocol.http.JoiList;
import com.github.enerccio.joi.protocol.http.JoiSummary;
import com.github.enerccio.joi.protocol.http.ServerInfo;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.ToolUse;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The node in a terminal: logs in to the server (a token in {@code JOI_TOKEN}, the device grant, or none when the
 * server runs without auth), picks her (the one chosen last, else asks), downloads her body's files into the cache,
 * connects and chats in text. What her body would play is printed ({@link AvatarDirector}); a non-looping animation
 * is taken as played after a moment. Commands: {@code /stop}, {@code /sleep}, {@code /quit}.
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
        NodeSettings settings = NodeSettings.standard();
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
        NodeSocket socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void turnStarted(String turn, String cause) {
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
                List.of(), null, null);
        Manifest manifest = new Manifest(1, System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("os.arch"), null, List.of());
        try {
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
                    later.shutdownNow();
                    return;
                }
                case "/stop" -> socket.stop(null);
                case "/sleep" -> {
                    synchronized (lock) {
                        director.fallAsleep();
                    }
                }
                default -> socket.say(line, false, false);
            }
        }
        socket.close();
        later.shutdownNow();
    }

    private void login(ServerClient client, NodeSettings settings, ServerInfo info, String nodeName) throws IOException, InterruptedException {
        NodeSettings.Credentials credentials = settings.credentials();
        String env = System.getenv("JOI_TOKEN");
        if (env != null && !env.isBlank()) {
            credentials.token = env.strip();
            settings.saveCredentials();
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
