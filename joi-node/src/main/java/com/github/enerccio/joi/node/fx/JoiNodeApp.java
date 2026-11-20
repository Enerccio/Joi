package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.*;
import com.github.enerccio.joi.node.renderer.BodyFiles;
import com.github.enerccio.joi.node.renderer.LookingGlassTuning;
import com.github.enerccio.joi.node.renderer.RendererServer;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.http.*;
import com.github.enerccio.joi.protocol.node.Hello;
import com.github.enerccio.joi.protocol.node.Manifest;
import com.github.enerccio.joi.protocol.node.NodePart;
import com.github.enerccio.joi.protocol.node.Welcome;
import com.github.enerccio.joi.protocol.oauth.DeviceAuthorization;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Window;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * The node as a desktop app (see {@code DESIGN.md} → Node → The first node): the server and its login (a token, the
 * device grant, or none), her pick (the one chosen last when she still exists), a loading screen while her body's
 * files download, then the chat beside her body ({@link AvatarPane}), which pops out into a window of its own and goes
 * fullscreen (the Looking Glass). Text chat, her voice played back ({@link SpeechPlayer}).
 * <p>
 * The Looking Glass: the pane's button moves her onto it ({@link LookingGlassWindow}, the display found through
 * Looking Glass Bridge) and back, and tells the server, so she reacts. The choice is remembered: a node that was left
 * with her on the Looking Glass opens it again as it starts (without telling the server: she was there already), and one
 * where it could not be opened does not try again until the button is pressed.
 * <p>
 * Her body is the renderer page ({@link RendererServer}): her cached files unpacked ({@link BodyFiles}) and served on
 * localhost and shown in the pane by Chromium (JCEF); without it, it opens in a browser.
 */
public class JoiNodeApp extends Application {

    /** How many of her latest exchanges the chat starts with. */
    private static final int HISTORY = 50;
    /** How long the user has to leave her parts alone before what they changed goes to the server, in one batch. */
    private static final long PART_QUIET_MILLIS = 4000;
    /**
     * How long the server has to answer a batch before the node asks what it has; three times (it may be her deciding),
     * then the controls are freed and show what is kept. How long her reaction is waited for when it is to come.
     */
    private static final PartStaging.Timing PART_TIMING = new PartStaging.Timing(PART_QUIET_MILLIS, 15000, 3, 40000);
    /** The cause of her turn about what was changed in her parts. */
    private static final String PART_SWITCHED = "part_switched";

    private final NodeSettings settings = NodeSettings.standard();
    private Stage stage;
    /** What the scene is: the Edit menu above the screen shown. */
    private final BorderPane frame = new BorderPane();
    private ServerClient client;
    private NodeSocket socket;
    private java.util.concurrent.ScheduledExecutorService stagingTimer;
    /** The turn of hers about the parts that her reaction to is waited for, null for none. */
    private volatile String partsTurn;
    private RendererServer renderer;
    private LookingGlassWindow lookingGlass;

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
        frame.setTop(editMenu());
        frame.setCenter(new StackPane());
        stage.setScene(new Scene(frame, 1100, 700));
        // the Looking Glass window would keep the app going: closing this one quits
        stage.setOnHidden(e -> Platform.exit());
        stage.show();
        NodeSettings.Node node = settings.node();
        showServer(node.server == null ? "http://localhost:8080" : node.server, null);
    }

    @Override
    public void stop() {
        if (stagingTimer != null) {
            stagingTimer.shutdownNow();
        }
        if (socket != null) {
            socket.close();
        }
        if (lookingGlass != null) {
            lookingGlass.quit();
        }
        if (renderer != null) {
            renderer.close();
        }
        Chromium.dispose();
        // Chromium's and AWT's threads would keep the process alive after its window closed
        Runtime.getRuntime().halt(0);
    }

    /** Whether the message typed in the chat goes with the change of her parts: she gets both as one message. */
    private static boolean askSendTyped() {
        ButtonType together = new ButtonType("Send together", ButtonBar.ButtonData.YES);
        ButtonType only = new ButtonType("Change only", ButtonBar.ButtonData.NO);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "You have typed a message. Send it together with the change of her parts? She gets both as one message.",
                together, only);
        ask.setHeaderText("Send your message with the change?");
        return ask.showAndWait().orElse(only) == together;
    }

    private void show(Parent view) {
        frame.setCenter(view);
    }

    /**
     * The Edit menu: on macOS the keys of cut, copy, paste and select all are the menu's, and an app without the menu gets
     * none of them (not even in a text field). It is the system's menu bar there, a bar above the window elsewhere.
     */
    private static MenuBar editMenu() {
        MenuItem cut = editItem("Cut", KeyCode.X, TextInputControl::cut);
        MenuItem copy = editItem("Copy", KeyCode.C, TextInputControl::copy);
        MenuItem paste = editItem("Paste", KeyCode.V, TextInputControl::paste);
        MenuItem all = editItem("Select All", KeyCode.A, TextInputControl::selectAll);
        MenuBar bar = new MenuBar(new Menu("Edit", null, cut, copy, paste, new SeparatorMenuItem(), all));
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private static MenuItem editItem(String text, KeyCode key, java.util.function.Consumer<TextInputControl> action) {
        MenuItem item = new MenuItem(text);
        item.setAccelerator(new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN));
        item.setOnAction(e -> {
            for (Window window : Window.getWindows()) {
                if (window.isFocused() && window.getScene() != null
                        && window.getScene().getFocusOwner() instanceof TextInputControl field) {
                    action.accept(field);
                    return;
                }
            }
        });
        return item;
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
        List<String> known;
        try {
            known = settings.servers();
        } catch (IOException e) {
            known = List.of();
        }
        ComboBox<String> address = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(known));
        address.setEditable(true);
        address.setMaxWidth(Double.MAX_VALUE);
        address.setPromptText("http://host:8080");
        address.getEditor().setText(server);
        TextField token = new TextField();
        Label status = new Label(problem == null ? "" : problem);
        Button connect = new Button("Connect");
        connect.setDefaultButton(true);
        Button forget = new Button("Forget this server");
        forget.setTooltip(new Tooltip("Removes the server from the list, with its token or pairing"));
        // a server known has its token or pairing kept: nothing to type
        Runnable shown = () -> {
            String typed = address.getEditor().getText();
            boolean saved = address.getItems().contains(NodeSettings.normalise(typed));
            forget.setDisable(!saved);
            token.setPromptText(saved ? "Saved for this server (a new token replaces it)"
                    : "A token from User Settings → Access (empty to pair the node)");
        };
        address.getEditor().textProperty().addListener((o, a, b) -> shown.run());
        shown.run();
        forget.setOnAction(e -> {
            String typed = NodeSettings.normalise(address.getEditor().getText());
            try {
                settings.forget(typed);
            } catch (IOException ex) {
                status.setText(ex.getMessage());
                return;
            }
            address.getItems().remove(typed);
            address.getEditor().clear();
            token.clear();
            status.setText("Forgot " + typed);
            shown.run();
        });
        connect.setOnAction(e -> {
            connect.setDisable(true);
            status.setText("Connecting...");
            login(NodeSettings.normalise(address.getEditor().getText()), token.getText().strip(), status, () -> connect.setDisable(false));
        });
        show(new StackPane(centered(new Label("Joi server"), address, token, new HBox(8, connect, forget), status)));
    }

    private void login(String server, String token, Label status, Runnable failed) {
        Task<ServerInfo> task = new Task<>() {
            @Override
            protected ServerInfo call() throws Exception {
                NodeSettings.Node node = settings.node();
                node.server = NodeSettings.normalise(server);
                settings.saveNode();
                client = new ServerClient(node.server, settings);
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
                List<JoiSummary> jois = client.jois().jois();
                // in: this server is offered again, with what got the node in
                settings.connected(settings.node().server);
                return jois;
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
    private record Loaded(JoiSummary summary, JoiInfo joi, List<ChatEntry> history, BodyInfo body, Map<String, String> animations,
                          BodyFiles files, org.cef.CefClient chromium) {
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
                List<ChatEntry> history;
                try {
                    history = client.history(chosen.id(), HISTORY).messages();
                } catch (ServerClient.ServerException e) {
                    // a server before 1.3: the chat starts empty
                    System.err.println("No chat history: " + e.getMessage());
                    history = List.of();
                }
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
                return new Loaded(chosen, joi, history, body, names, files, chromium);
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
        // the parts as the server has them (kept on her body): shown here, and switched in the controls
        List<BodyToggle> toggles = new ArrayList<>(loaded.body() == null || loaded.body().toggles() == null ? List.of() : loaded.body().toggles());
        renderer.toggles(toggles);
        AvatarPane avatarPane = new AvatarPane(loaded.animations(), renderer, loaded.chromium(), url -> getHostServices().showDocument(url));
        ChatPane chat = new ChatPane(loaded.summary().name());
        chat.history(loaded.history());
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

        // the controls wait for the server: a change they make is told to it, which keeps it
        ControlsPane controls = new ControlsPane();
        controls.parts(loaded.body() == null ? null : loaded.body().parts());
        // what the user changes is held back for a few seconds and sent together; her body shows what the server kept
        Map<String, BodyPart> parts = new HashMap<>();
        Map<String, String> kept = new HashMap<>();
        for (BodyPart part : loaded.body() == null || loaded.body().parts() == null ? List.<BodyPart>of() : loaded.body().parts()) {
            parts.put(part.id(), part);
            kept.put(part.id(), part.state());
        }
        stagingTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Joi parts");
            thread.setDaemon(true);
            return thread;
        });
        java.util.concurrent.atomic.AtomicBoolean withReaction = new java.util.concurrent.atomic.AtomicBoolean(
                "reaction".equals(nodeSetting(node -> node.partsShown)));
        controls.partsOption(withReaction.get(), on -> {
            withReaction.set(on);
            try {
                settings.node().partsShown = on ? "reaction" : "kept";
                settings.saveNode();
            } catch (IOException e) {
                chat.note("The choice was not saved: " + e.getMessage());
            }
        });
        // her body changed on the server (she changed a part herself): read it again, and show what is not shown yet
        java.util.concurrent.atomic.AtomicReference<Runnable> resync = new java.util.concurrent.atomic.AtomicReference<>(() -> {
        });
        java.util.concurrent.atomic.AtomicBoolean resyncLater = new java.util.concurrent.atomic.AtomicBoolean();
        PartStaging staging = new PartStaging(PART_TIMING, stagingTimer,
                (id, part, state) -> socket.part(id, part, state),
                () -> {
                    // what the server has now, to know what became of a batch it did not answer
                    BodyInfo info = client.body(loaded.summary().id());
                    Map<String, String> states = new HashMap<>();
                    for (BodyPart part : info == null || info.parts() == null ? List.<BodyPart>of() : info.parts()) {
                        states.put(part.id(), part.state());
                    }
                    return states;
                },
                withReaction::get,
                locked -> Platform.runLater(() -> controls.lockParts(locked)),
                (done, notKept, refused) -> Platform.runLater(() -> {
                    // a change of hers that came while the user's batch waited is read now
                    if (resyncLater.getAndSet(false)) {
                        resync.get().run();
                    }
                    for (com.github.enerccio.joi.protocol.node.PartSwitch change : done) {
                        keepPart(parts, kept, toggles, controls, change);
                    }
                    for (com.github.enerccio.joi.protocol.node.PartSwitch change : notKept) {
                        controls.showPart(change.part(), kept.get(change.part()));
                    }
                    if (!notKept.isEmpty()) {
                        chat.note("The server did not keep " + notKept.size() + (notKept.size() == 1 ? " change" : " changes")
                                + " of her parts.");
                    }
                    for (PartStaging.Refusal refusal : refused) {
                        controls.showPart(refusal.change().part(), kept.get(refusal.change().part()));
                        BodyPart part = parts.get(refusal.change().part());
                        chat.note("She did not allow it: " + (part == null ? refusal.change().part() : part.name())
                                + (refusal.reason() == null ? "" : " (" + refusal.reason() + ")"));
                    }
                }));
        // words typed in the chat go with the change when the user says so, asked as the batch is about to be sent: the
        // server tells her both in one turn
        staging.withWords(() -> {
            java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
            Platform.runLater(() -> answer.complete(chat.hasTyped() && askSendTyped()));
            try {
                return answer.get(10, java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception e) {
                return false;
            }
        }, sent -> {
            if (sent > 0) {
                Platform.runLater(chat::sayWithParts);
            }
        });
        resync.set(() -> {
            if (staging.locked() || staging.pending()) {
                // the user's own change comes first: the body is read again when it is resolved
                resyncLater.set(true);
                return;
            }
            Thread.ofVirtual().name("Joi body").start(() -> {
                try {
                    BodyInfo info = client.body(loaded.summary().id());
                    if (info == null || info.parts() == null) {
                        return;
                    }
                    Platform.runLater(() -> {
                        for (BodyPart part : info.parts()) {
                            if (!java.util.Objects.equals(kept.get(part.id()), part.state())) {
                                keepPart(parts, kept, toggles, controls,
                                        new com.github.enerccio.joi.protocol.node.PartSwitch(part.id(), part.state()));
                            }
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> chat.note("Her body could not be read again: " + e.getMessage()));
                }
            });
        });
        controls.setOnSwitch((part, state) -> {
            if (!staging.change(part.id(), state.id())) {
                controls.showPart(part.id(), kept.get(part.id()));
            }
        });
        // offline nothing can be done: the controls of her parts, the chat and the Looking Glass wait for the connection
        java.util.function.Consumer<Boolean> online = on -> {
            controls.setDisable(!on);
            chat.setOnline(on);
            avatarPane.setOnline(on);
            // she stands still and gray while the server is gone, here and on the Looking Glass
            if (renderer != null) {
                renderer.offline(!on);
            }
        };
        online.accept(false);

        // with a voice her words wait for it
        boolean voiced = loaded.joi().voice() != null;
        socket = new NodeSocket(new NodeSocket.Listener() {
            @Override
            public void turnStarted(String turn, String cause) {
                if (PART_SWITCHED.equals(cause)) {
                    partsTurn = turn;
                }
                Platform.runLater(() -> director.turnStarted(turn, cause, voiced));
            }

            /** Her reaction to the parts began (or the turn is over): what waited for it is shown. */
            private void reacted(String turn) {
                if (turn != null && turn.equals(partsTurn)) {
                    partsTurn = null;
                    staging.reaction();
                }
            }

            @Override
            public void event(com.github.enerccio.joi.protocol.node.ServerEvent event) {
                if ("body".equals(event.name())) {
                    Platform.runLater(() -> resync.get().run());
                }
            }

            @Override
            public void partKept(String id, com.github.enerccio.joi.protocol.node.PartKept changed) {
                if (!staging.kept(id, changed.told())) {
                    // answered after the node gave up on it: the server has it, so it is shown
                    Platform.runLater(() -> keepPart(parts, kept, toggles, controls,
                            new com.github.enerccio.joi.protocol.node.PartSwitch(changed.part(), changed.state())));
                }
            }

            @Override
            public void partRefused(String id, com.github.enerccio.joi.protocol.node.PartRefused refusal) {
                staging.refused(id, refusal.reason());
            }

            @Override
            public void animation(com.github.enerccio.joi.protocol.node.PlayAnimation play) {
                Platform.runLater(() -> director.gesture(play.animation(), play.loops()));
            }

            @Override
            public void text(String turn, String delta) {
                reacted(turn);
                Platform.runLater(() -> director.text(turn, delta));
            }

            @Override
            public void reply(String turn, ChatReply reply) {
                Platform.runLater(() -> director.reply(turn, reply));
            }

            @Override
            public void audio(String turn, com.github.enerccio.joi.protocol.http.AudioChunk chunk) {
                reacted(turn);
                Platform.runLater(() -> director.audio(turn, chunk));
            }

            @Override
            public void done(String turn, Done done) {
                reacted(turn);
                Platform.runLater(() -> director.done(turn));
            }

            @Override
            public void failed(String ref, ApiError error) {
                if (staging.failed(ref)) {
                    return;
                }
                reacted(ref);
                Platform.runLater(() -> director.failed(ref, error));
            }

            @Override
            public void disconnected(int code, String reason) {
                Platform.runLater(() -> {
                    chat.note("Disconnected: " + reason + " (" + code + "). Connecting again…");
                    director.disconnected();
                    online.accept(false);
                    staging.cancel();
                });
            }

            @Override
            public void retrying(long waitSeconds) {
                Platform.runLater(() -> chat.waitingToReconnect(waitSeconds, () -> socket.reconnectNow()));
            }

            @Override
            public void connecting() {
                Platform.runLater(chat::reconnecting);
            }

            @Override
            public void reconnected(Welcome welcome) {
                Platform.runLater(() -> {
                    chat.connectedAgain();
                    chat.note("Connected again.");
                    online.accept(true);
                });
            }

            @Override
            public void closed(int code, String reason) {
                Platform.runLater(() -> {
                    chat.gone(reason + " (" + code + ")");
                    chat.note("Disconnected: " + reason + " (" + code + ")");
                    online.accept(false);
                    staging.cancel();
                });
            }
        });
        chat.setOnSay(text -> socket.say(text, true, false));
        chat.setOnSayWithParts(text -> socket.say(text, true, false, true));
        chat.setOnStop(() -> {
            // talked over: her words and her voice stop
            socket.stop(null);
            speech.stop();
            director.speechEnded();
        });

        avatarPane.setLookingGlassPanel(new LookingGlassTuningPane(nodeSetting(node -> node.lookingGlassTuning), tuning -> {
            renderer.tuning(tuning);
            try {
                settings.node().lookingGlassTuning = tuning;
                settings.saveNode();
            } catch (IOException e) {
                System.err.println("The Looking Glass tuning was not saved: " + e.getMessage());
            }
        }));
        LookingGlassTuning tuning = nodeSetting(node -> node.lookingGlassTuning);
        renderer.tuning(tuning == null ? new LookingGlassTuning() : tuning);
        avatarPane.setOnLookingGlass(on -> {
            if (on) {
                openLookingGlass(loaded, avatarPane, chat, true);
            } else if (lookingGlass != null) {
                lookingGlass.close();
            }
        });

        SplitPane split = new SplitPane(chat, controls, avatarPane);
        split.setDividerPositions(0.3, 0.55);
        BorderPane root = new BorderPane(split);
        show(root);
        stage.setTitle("Joi: " + loaded.summary().name());
        if (Boolean.TRUE.equals(nodeSetting(node -> node.lookingGlass)) && loaded.chromium() != null) {
            openLookingGlass(loaded, avatarPane, chat, false);
        }

        background(new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                NodeSettings.Node node = settings.node();
                Hello hello = new Hello(JoiProtocol.NODE_VERSION, node.nodeId, node.name, node.kind == null ? "desktop" : node.kind,
                        "joi-node (JavaFX)", loaded.summary().id(), List.of(NodePart.AUDIO_OUT, NodePart.AVATAR), null, null);
                Manifest manifest = new Manifest(1, System.getProperty("os.name") + " " + System.getProperty("os.version"),
                        System.getProperty("os.arch"), null, List.of());
                socket.connect(client.nodeSocket(), client.authorization(), hello, manifest).get();
                Platform.runLater(() -> {
                    director.start();
                    online.accept(true);
                });
                return null;
            }
        });
    }

    /** The server kept a part in a state: it is the kept one now, and shown on her body, and in the controls. */
    private void keepPart(Map<String, BodyPart> parts, Map<String, String> kept, List<BodyToggle> toggles, ControlsPane controls,
                          com.github.enerccio.joi.protocol.node.PartSwitch change) {
        BodyPart part = parts.get(change.part());
        BodyPartState state = part == null ? null : part.states().stream()
                .filter(candidate -> candidate.id().equals(change.state())).findFirst().orElse(null);
        if (state != null) {
            kept.put(part.id(), state.id());
            controls.showPart(part.id(), state.id());
            showState(toggles, state);
        }
    }

    /**
     * A part of her body was put in a state, and the server kept it: shown on her body here and on the Looking Glass.
     *
     * @param toggles every toggle of her model as it is now; the state's are set
     */
    private void showState(List<BodyToggle> toggles, BodyPartState state) {
        for (BodyToggle set : state.toggles()) {
            for (int i = 0; i < toggles.size(); i++) {
                BodyToggle toggle = toggles.get(i);
                if (toggle.mesh().equals(set.mesh()) && java.util.Objects.equals(toggle.key(), set.key())) {
                    toggles.set(i, set);
                }
            }
        }
        renderer.toggles(List.copyOf(toggles));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the Looking Glass
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Moves her onto the Looking Glass: by the user ({@code asked}: the server is told, so she reacts), or as the node
     * starts, where she was left. When it can't be done, it is remembered, and not tried at start again.
     */
    private void openLookingGlass(Loaded loaded, AvatarPane avatarPane, ChatPane chat, boolean asked) {
        if (lookingGlass != null) {
            return;
        }
        Thread.ofVirtual().name("Joi Looking Glass").start(() -> {
            LookingGlassBridge.Device device;
            try {
                device = new LookingGlassBridge().find();
            } catch (IOException e) {
                Platform.runLater(() -> lookingGlassFailed(avatarPane, chat, e.getMessage(), asked));
                return;
            }
            Platform.runLater(() -> {
                if (lookingGlass != null) {
                    return;
                }
                LookingGlassWindow window;
                try {
                    renderer.lookingGlass(device.renderer());
                    window = LookingGlassWindow.open(device, settings.dir(), nodeSetting(node -> node.lookingGlassBrowser),
                            renderer.lookingGlassUrl().toString());
                } catch (IOException | RuntimeException e) {
                    lookingGlassFailed(avatarPane, chat, e.getMessage(), asked);
                    return;
                }
                lookingGlass = window;
                avatarPane.lookingGlass(true);
                window.setOnClosed(() -> {
                    lookingGlass = null;
                    avatarPane.lookingGlass(false);
                    if (window.closedOnPurpose()) {
                        rememberLookingGlass(false);
                    } else {
                        // unplugged: tried again at the next start
                        chat.note("The Looking Glass went away.");
                    }
                    socket.display(false);
                });
                rememberLookingGlass(true);
                if (asked) {
                    socket.display(true);
                }
            });
        });
    }

    private void lookingGlassFailed(AvatarPane avatarPane, ChatPane chat, String problem, boolean asked) {
        avatarPane.lookingGlass(false);
        rememberLookingGlass(false);
        chat.note("No Looking Glass: " + problem + (asked ? "" : ". It is not tried at start again until you open it."));
    }

    private void rememberLookingGlass(boolean on) {
        try {
            settings.node().lookingGlass = on;
            settings.saveNode();
        } catch (IOException e) {
            System.err.println("The Looking Glass choice was not saved: " + e.getMessage());
        }
    }

    private <T> T nodeSetting(java.util.function.Function<NodeSettings.Node, T> setting) {
        try {
            return setting.apply(settings.node());
        } catch (IOException e) {
            return null;
        }
    }

    /** The transcript and the input. */
    static final class ChatPane extends BorderPane {

        private final VBox lines = new VBox(8);
        private final ScrollPane scroll = new ScrollPane(lines);
        private final TextField input = new TextField();
        /** Shown while offline: when the next try is, and a link to make it now. */
        private final Label offlineState = new Label();
        private final Hyperlink reconnect = new Hyperlink("Reconnect now");
        private final HBox offline = new HBox(8, offlineState, reconnect);
        private javafx.animation.Timeline countdown;
        private int left;
        private final Button send = new Button("Send");
        private final Button stop = new Button("Stop");
        private final String name;
        private final Map<String, Label> streaming = new HashMap<>();
        private java.util.function.Consumer<String> onSay = text -> {
        };
        private java.util.function.Consumer<String> onSayWithParts = text -> {
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
            send.setOnAction(e -> send());
            stop.setOnAction(e -> onStop.run());
            HBox bottom = new HBox(8, input, send, stop);
            HBox.setHgrow(input, Priority.ALWAYS);
            bottom.setPadding(new Insets(8));
            offline.setPadding(new Insets(6, 8, 0, 8));
            offline.setAlignment(Pos.CENTER_LEFT);
            offline.setVisible(false);
            offline.setManaged(false);
            setCenter(scroll);
            setBottom(new VBox(offline, bottom));
        }

        /** Offline she can't be talked to (nor stopped): what is typed stays where it is until the connection is back. */
        void setOnline(boolean online) {
            input.setDisable(!online);
            send.setDisable(!online);
            stop.setDisable(!online);
            input.setPromptText(online ? "Talk to " + name : "Not connected…");
        }

        /** The next try to connect is in {@code seconds}: counted down, with a link to try now. */
        void waitingToReconnect(long seconds, Runnable now) {
            stopCountdown();
            left = (int) seconds;
            reconnect.setVisible(true);
            reconnect.setOnAction(e -> now.run());
            showOffline(offlineText());
            countdown = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                if (--left > 0) {
                    offlineState.setText(offlineText());
                } else {
                    offlineState.setText("Connecting…");
                }
            }));
            countdown.setCycleCount((int) Math.max(1, seconds));
            countdown.play();
        }

        private String offlineText() {
            return "Not connected. Connecting again in " + left + " s…";
        }

        /** A try to connect is running. */
        void reconnecting() {
            stopCountdown();
            reconnect.setVisible(false);
            showOffline("Connecting…");
        }

        /** Connected: nothing to say about it. */
        void connectedAgain() {
            stopCountdown();
            offline.setVisible(false);
            offline.setManaged(false);
        }

        /** It will not connect again by itself (the server said so): why, with no link. */
        void gone(String reason) {
            stopCountdown();
            reconnect.setVisible(false);
            showOffline("Disconnected: " + reason);
        }

        private void showOffline(String text) {
            offlineState.setText(text);
            offline.setVisible(true);
            offline.setManaged(true);
        }

        private void stopCountdown() {
            if (countdown != null) {
                countdown.stop();
                countdown = null;
            }
        }

        void setOnSay(java.util.function.Consumer<String> onSay) {
            this.onSay = onSay;
        }

        /** What is said when the typed words go with a change of her parts. */
        void setOnSayWithParts(java.util.function.Consumer<String> onSayWithParts) {
            this.onSayWithParts = onSayWithParts;
        }

        /** Whether words are typed and not sent. */
        boolean hasTyped() {
            return !input.getText().isBlank();
        }

        /** Sends the typed words as the part of one message with the change of her parts just sent (nothing when none are typed). */
        void sayWithParts() {
            String text = input.getText().strip();
            if (!text.isEmpty()) {
                input.clear();
                add("You: " + text);
                onSayWithParts.accept(text);
            }
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

        /** Her history from the server: the chat goes on where it was. */
        void history(List<ChatEntry> entries) {
            for (ChatEntry entry : entries) {
                if (entry.text() != null) {
                    add("You: " + entry.text());
                }
                if (entry.reply() != null) {
                    add(name + ": " + entry.reply());
                }
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
