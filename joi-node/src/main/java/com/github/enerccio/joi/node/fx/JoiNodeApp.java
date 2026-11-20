package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.AvatarDirector;
import com.github.enerccio.joi.node.NodeSettings;
import com.github.enerccio.joi.node.NodeSocket;
import com.github.enerccio.joi.node.ServerClient;
import com.github.enerccio.joi.node.renderer.BodyFiles;
import com.github.enerccio.joi.node.renderer.RendererServer;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.ApiError;
import com.github.enerccio.joi.protocol.http.AuthMethod;
import com.github.enerccio.joi.protocol.http.BodyAnimation;
import com.github.enerccio.joi.protocol.http.BodyInfo;
import com.github.enerccio.joi.protocol.http.ChatReply;
import com.github.enerccio.joi.protocol.http.Done;
import com.github.enerccio.joi.protocol.http.Download;
import com.github.enerccio.joi.protocol.http.JoiInfo;
import com.github.enerccio.joi.protocol.http.JoiSummary;
import com.github.enerccio.joi.protocol.http.ServerInfo;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodePart;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The node as a desktop app (see {@code DESIGN.md} → Node → The first node): the server and its login (a token, the
 * device grant, or none), her pick (the one chosen last when she still exists), a loading screen while her body's
 * files download, then the chat beside her body ({@link AvatarPane}), which pops out into a window of its own and goes
 * fullscreen (the Looking Glass). Text chat, her voice played back ({@link SpeechPlayer}).
 * <p>
 * Her body is the renderer page ({@link RendererServer}): her cached files unpacked ({@link BodyFiles}) and served on
 * localhost and shown in the pane by Chromium (JCEF); without it, it opens in a browser.
 */
public class JoiNodeApp extends Application {

    private final NodeSettings settings = NodeSettings.standard();
    private Stage stage;
    private ServerClient client;
    private NodeSocket socket;
    private RendererServer renderer;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Uncaught in " + thread.getName() + ":");
            error.printStackTrace();
        });
        this.stage = stage;
        stage.setTitle("Joi");
        stage.setScene(new Scene(new StackPane(), 1100, 700));
        stage.show();
        NodeSettings.Node node = settings.node();
        showServer(node.server == null ? "http://localhost:8080" : node.server, null);
    }

    @Override
    public void stop() {
        if (socket != null) {
            socket.close();
        }
        if (renderer != null) {
            renderer.close();
        }
        Chromium.dispose();
        // Chromium's and AWT's threads would keep the process alive after its window closed
        Runtime.getRuntime().halt(0);
    }

    private void show(Parent view) {
        stage.getScene().setRoot(view);
    }

    private static VBox centered(Node... children) {
        VBox box = new VBox(12, children);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        box.setMaxWidth(520);
        return box;
    }

    /** A background job; its failure is shown on the server screen. */
    private <T> void background(Task<T> task) {
        task.setOnFailed(e -> showServer(client == null ? "" : client.server(), String.valueOf(task.getException().getMessage())));
        Thread.ofVirtual().name("Joi node").start(task);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the server and the login
    // ----------------------------------------------------------------------------------------------------------------

    private void showServer(String server, String problem) {
        TextField address = new TextField(server);
        address.setPromptText("http://host:8080");
        TextField token = new TextField();
        token.setPromptText("A token from User Settings → Access (empty to pair the node)");
        Label status = new Label(problem == null ? "" : problem);
        Button connect = new Button("Connect");
        connect.setDefaultButton(true);
        connect.setOnAction(e -> {
            connect.setDisable(true);
            status.setText("Connecting...");
            login(address.getText().strip(), token.getText().strip(), status, () -> connect.setDisable(false));
        });
        show(new StackPane(centered(new Label("Joi server"), address, token, connect, status)));
    }

    private void login(String server, String token, Label status, Runnable failed) {
        Task<ServerInfo> task = new Task<>() {
            @Override
            protected ServerInfo call() throws Exception {
                NodeSettings.Node node = settings.node();
                node.server = server;
                settings.saveNode();
                client = new ServerClient(server, settings);
                if (!token.isEmpty()) {
                    settings.credentials().token = token;
                    settings.saveCredentials();
                }
                return client.info();
            }
        };
        task.setOnSucceeded(e -> {
            ServerInfo info = task.getValue();
            try {
                List<AuthMethod> methods = info.auth().methods();
                if (settings.credentials().authorization() != null || methods.contains(AuthMethod.NONE)) {
                    loadJois();
                } else if (methods.contains(AuthMethod.OAUTH)) {
                    pair();
                } else {
                    status.setText("The server wants a token: make one in User Settings → Access.");
                    failed.run();
                }
            } catch (IOException ex) {
                status.setText(ex.getMessage());
                failed.run();
            }
        });
        task.setOnFailed(e -> {
            status.setText("Can't reach " + server + ": " + task.getException().getMessage());
            failed.run();
        });
        Thread.ofVirtual().start(task);
    }

    /** The OAuth device grant: a code the user approves in the server's UI. */
    private void pair() throws IOException {
        String name = settings.node().name;
        Label code = new Label("...");
        code.setStyle("-fx-font-size: 28px; -fx-font-family: monospace;");
        Label where = new Label();
        where.setWrapText(true);
        show(new StackPane(centered(new Label("Pair this node"), code, where)));
        background(new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                DeviceAuthorization codes = client.startDeviceGrant(name);
                Platform.runLater(() -> {
                    code.setText(codes.userCode());
                    where.setText("Open " + codes.verificationUriComplete() + " and approve it (logged in as you).");
                });
                long deadline = System.currentTimeMillis() + codes.expiresIn() * 1000;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(Math.max(1, codes.interval()) * 1000);
                    if (client.pollDeviceGrant(codes.deviceCode())) {
                        Platform.runLater(JoiNodeApp.this::loadJois);
                        return null;
                    }
                }
                throw new IOException("The code expired before it was approved");
            }
        });
    }

    // ----------------------------------------------------------------------------------------------------------------
    // her pick
    // ----------------------------------------------------------------------------------------------------------------

    private void loadJois() {
        background(new Task<List<JoiSummary>>() {
            {
                setOnSucceeded(e -> chooseJoi(getValue()));
            }

            @Override
            protected List<JoiSummary> call() throws Exception {
                return client.jois().jois();
            }
        });
    }

    private void chooseJoi(List<JoiSummary> jois) {
        String last;
        try {
            last = settings.node().joi;
        } catch (IOException e) {
            last = null;
        }
        for (JoiSummary joi : jois) {
            if (joi.id().equals(last)) {
                load(joi);
                return;
            }
        }
        if (jois.size() == 1) {
            load(jois.getFirst());
            return;
        }
        ListView<JoiSummary> list = new ListView<>();
        list.getItems().setAll(jois);
        list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(JoiSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        Button open = new Button("Open");
        open.setDefaultButton(true);
        open.setOnAction(e -> {
            JoiSummary chosen = list.getSelectionModel().getSelectedItem();
            if (chosen != null) {
                load(chosen);
            }
        });
        Label empty = new Label(jois.isEmpty() ? "There is no assistant yet: create one in the server's UI." : "");
        show(new StackPane(centered(new Label("Who?"), list, open, empty)));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // loading her body
    // ----------------------------------------------------------------------------------------------------------------

    /** What the loading screen got: her, her body (null for none) and the names of her animations. */
    private record Loaded(JoiSummary summary, JoiInfo joi, BodyInfo body, Map<String, String> animations, BodyFiles files,
                          org.cef.CefClient chromium) {
    }

    private void load(JoiSummary chosen) {
        Label what = new Label("Loading " + chosen.name() + "...");
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(400);
        show(new StackPane(centered(what, progress)));
        background(new Task<Loaded>() {
            {
                setOnSucceeded(e -> connect(getValue()));
            }

            @Override
            protected Loaded call() throws Exception {
                NodeSettings.Node node = settings.node();
                node.joi = chosen.id();
                settings.saveNode();
                JoiInfo joi = client.joi(chosen.id());
                BodyInfo body = client.body(chosen.id());
                Map<String, String> names = new HashMap<>();
                Path joim = null;
                Path joia = null;
                if (body != null) {
                    if (body.model() != null && body.model().joim() != null) {
                        joim = fetch("her body", body.model().joim());
                    }
                    if (body.animations() != null) {
                        for (BodyAnimation animation : body.animations().animations()) {
                            names.put(animation.id(), animation.name());
                        }
                        if (body.animations().joia() != null) {
                            joia = fetch("her animations", body.animations().joia());
                        }
                    }
                }
                Platform.runLater(() -> {
                    what.setText("Unpacking her body...");
                    progress.setProgress(-1);
                });
                BodyFiles files = BodyFiles.unpack(joim, joia, settings.cache().resolve("unpacked"));
                Platform.runLater(() -> what.setText("Starting her renderer..."));
                org.cef.CefClient chromium;
                try {
                    chromium = Chromium.client(settings.dir());
                } catch (Exception e) {
                    // her body only in a browser then
                    System.err.println("Chromium did not start: " + e);
                    chromium = null;
                }
                return new Loaded(chosen, joi, body, names, files, chromium);
            }

            private Path fetch(String label, Download file) throws Exception {
                Platform.runLater(() -> what.setText("Downloading " + label + "..."));
                return client.download(file, bytes -> Platform.runLater(() -> progress.setProgress(file.size() <= 0 ? -1 : (double) bytes / file.size())));
            }
        });
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the chat beside her body
    // ----------------------------------------------------------------------------------------------------------------

    private void connect(Loaded loaded) {
        try {
            if (renderer == null) {
                renderer = new RendererServer();
            }
        } catch (java.io.IOException e) {
            showServer(client.server(), "The renderer could not start: " + e.getMessage());
            return;
        }
        renderer.show(loaded.files());
        AvatarPane avatarPane = new AvatarPane(loaded.animations(), renderer, loaded.chromium(), url -> getHostServices().showDocument(url));
        ChatPane chat = new ChatPane(loaded.summary().name());
        SpeechPlayer speech = new SpeechPlayer();
        AvatarDirector director = new AvatarDirector(new AvatarDirector.Avatar() {
            @Override
            public void play(String animation, boolean loop) {
                avatarPane.play(animation, loop);
            }

            @Override
            public void text(String turn, String delta) {
                chat.herText(turn, delta);
            }

            @Override
            public void reply(String turn, ChatReply reply) {
                chat.herReply(turn, reply.text());
            }

            @Override
            public void speak(String turn, com.github.enerccio.joi.protocol.http.AudioChunk chunk) {
                speech.play(chunk);
            }

            @Override
            public void failed(String turn, ApiError error) {
                chat.note(error.code().equals("stopped") ? "(stopped)" : error.code() + ": " + error.message());
            }

            @Override
            public void state(AvatarDirector.State state) {
                avatarPane.state(state);
            }
        }, loaded.joi().animations(), new Random());
        avatarPane.setOnAnimationEnded(director::animationEnded);
        speech.setOnEnded(director::speechEnded);

        socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void turnStarted(String turn, String cause) {
                Platform.runLater(() -> director.turnStarted(turn, cause, true));
            }

            @Override
            public void text(String turn, String delta) {
                Platform.runLater(() -> director.text(turn, delta));
            }

            @Override
            public void reply(String turn, ChatReply reply) {
                Platform.runLater(() -> director.reply(turn, reply));
            }

            @Override
            public void audio(String turn, com.github.enerccio.joi.protocol.http.AudioChunk chunk) {
                Platform.runLater(() -> director.audio(turn, chunk));
            }

            @Override
            public void done(String turn, Done done) {
                Platform.runLater(() -> director.done(turn));
            }

            @Override
            public void failed(String ref, ApiError error) {
                Platform.runLater(() -> director.failed(ref, error));
            }

            @Override
            public void closed(int code, String reason) {
                Platform.runLater(() -> chat.note("Disconnected: " + reason + " (" + code + ")"));
            }
        });
        chat.setOnSay(text -> socket.say(text, true, false));
        chat.setOnStop(() -> {
            // talked over: her words and her voice stop
            socket.stop(null);
            speech.stop();
            director.speechEnded();
        });

        SplitPane split = new SplitPane(chat, avatarPane);
        split.setDividerPositions(0.45);
        BorderPane root = new BorderPane(split);
        show(root);
        stage.setTitle("Joi: " + loaded.summary().name());

        background(new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                NodeSettings.Node node = settings.node();
                Hello hello = new Hello(JoiProtocol.NODE_VERSION, node.nodeId, node.name, node.kind == null ? "desktop" : node.kind,
                        "joi-node (JavaFX)", loaded.summary().id(), List.of(NodePart.AUDIO_OUT, NodePart.AVATAR), null);
                Manifest manifest = new Manifest(1, System.getProperty("os.name") + " " + System.getProperty("os.version"),
                        System.getProperty("os.arch"), null, List.of());
                socket.connect(client.nodeSocket(), client.authorization(), hello, manifest).get();
                Platform.runLater(director::start);
                return null;
            }
        });
    }

    /** The transcript and the input. */
    static final class ChatPane extends BorderPane {

        private final VBox lines = new VBox(8);
        private final ScrollPane scroll = new ScrollPane(lines);
        private final TextField input = new TextField();
        private final String name;
        private final Map<String, Label> streaming = new HashMap<>();
        private java.util.function.Consumer<String> onSay = text -> {
        };
        private Runnable onStop = () -> {
        };

        ChatPane(String name) {
            this.name = name;
            lines.setPadding(new Insets(12));
            scroll.setFitToWidth(true);
            lines.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1));
            input.setPromptText("Talk to " + name);
            input.setOnAction(e -> send());
            Button send = new Button("Send");
            send.setOnAction(e -> send());
            Button stop = new Button("Stop");
            stop.setOnAction(e -> onStop.run());
            HBox bottom = new HBox(8, input, send, stop);
            HBox.setHgrow(input, Priority.ALWAYS);
            bottom.setPadding(new Insets(8));
            setCenter(scroll);
            setBottom(bottom);
        }

        void setOnSay(java.util.function.Consumer<String> onSay) {
            this.onSay = onSay;
        }

        void setOnStop(Runnable onStop) {
            this.onStop = onStop;
        }

        private void send() {
            String text = input.getText().strip();
            if (!text.isEmpty()) {
                input.clear();
                add("You: " + text);
                onSay.accept(text);
            }
        }

        void herText(String turn, String delta) {
            Label line = streaming.computeIfAbsent(turn, t -> add(name + ": "));
            line.setText(line.getText() + delta);
        }

        void herReply(String turn, String text) {
            Label line = streaming.remove(turn);
            if (line == null) {
                add(name + ": " + text);
            } else {
                line.setText(name + ": " + text);
            }
        }

        void note(String text) {
            Label line = add(text);
            line.setStyle("-fx-opacity: 0.6;");
        }

        private Label add(String text) {
            Label line = new Label(text);
            line.setWrapText(true);
            lines.getChildren().add(line);
            return line;
        }
    }
}
