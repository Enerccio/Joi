package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.browser.BrowserLink;
import com.github.enerccio.joi.node.harness.Harness;
import com.github.enerccio.joi.node.*;
import com.github.enerccio.joi.node.renderer.BodyFiles;
import com.github.enerccio.joi.node.renderer.LookingGlassTuning;
import com.github.enerccio.joi.node.renderer.RendererServer;
import com.github.enerccio.joi.node.voice.SpeechModel;
import com.github.enerccio.joi.node.voice.VoiceInput;
import com.github.enerccio.joi.node.voice.VoiceModels;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int HISTORY = 500;
    /** How long the user has to leave her parts alone before what they changed goes to the server, in one batch. */
    private static final long PART_QUIET_MILLIS = 4000;
    /**
     * How long the server has to answer a batch before the node asks what it has; three times (it may be her deciding),
     * then the controls are freed and show what is kept. How long her reaction is waited for when it is to come.
     */
    private static final PartStaging.Timing PART_TIMING = new PartStaging.Timing(PART_QUIET_MILLIS, 15000, 3, 40000);
    /** The cause of her turn about what was changed in her parts. */
    private static final String PART_SWITCHED = "part_switched";

    /** The node's home for this run: {@code --joi-home-dir} among the arguments, else the environment ({@link NodeSettings#resolve}). */
    private NodeSettings settings;
    private Stage stage;
    /** What the scene is: the Edit menu above the screen shown. */
    private final BorderPane frame = new BorderPane();
    private ServerClient client;
    /** The tasks of hers that wait for the user's answer, and the dialog that asks about each ({@code task} frames, since 1.7). */
    private final TaskQuestions taskQuestions = new TaskQuestions();
    /** What she may do with the notification tool, the user's choice ({@link Notifications}); works once she is loaded. */
    private final RadioMenuItem notifyOff = new RadioMenuItem("Off (she does not get the tool)");
    private final RadioMenuItem notifyAsk = new RadioMenuItem("Ask me each time");
    private final RadioMenuItem notifyAllow = new RadioMenuItem("Allow");
    private final MenuItem notifyTest = new MenuItem("Show a test notification");
    /** What she may do with the clipboard tool, the user's choice ({@link ClipboardOffers}): she may offer, the user sees each text and decides. */
    private final RadioMenuItem clipOff = new RadioMenuItem("Off (she does not get the tool)");
    private final RadioMenuItem clipAsk = new RadioMenuItem("Show me each offer, I decide");
    private final RadioMenuItem clipAllow = new RadioMenuItem("Allow (she copies without asking)");
    private final Menu clipMenu = new Menu("Clipboard offers", null, clipOff, clipAsk, clipAllow);
    private final Menu notifyMenu = new Menu("Notifications", null, notifyOff, notifyAsk, notifyAllow, new SeparatorMenuItem(), notifyTest);
    {
        ToggleGroup group = new ToggleGroup();
        notifyOff.setToggleGroup(group);
        notifyAsk.setToggleGroup(group);
        notifyAllow.setToggleGroup(group);
        notifyMenu.setDisable(true);
        ToggleGroup clipGroup = new ToggleGroup();
        clipOff.setToggleGroup(clipGroup);
        clipAsk.setToggleGroup(clipGroup);
        clipAllow.setToggleGroup(clipGroup);
        clipMenu.setDisable(true);
    }
    /** The browser companion's settings: it works once she is loaded. */
    private final MenuItem browserItem = new MenuItem("Browser…") {
        {
            setDisable(true);
        }
    };
    /** The browser companion's socket, opened once; the harness of the loaded assistant, null before. */
    private BrowserLink browserLink;
    private volatile Harness harness;
    /** The harness's settings (files, the shell, the user's folders): it works once she is loaded. */
    private final MenuItem harnessItem = new MenuItem("Files and shell…") {
        {
            setDisable(true);
        }
    };
    /** The menu item of the tools approved ahead of time: it works once she is loaded. */
    private final MenuItem approvals = new MenuItem("Auto-approved tools…") {
        {
            setDisable(true);
        }
    };
    private final Map<String, Alert> taskAlerts = new HashMap<>();
    private NodeSocket socket;
    /** The tools the user switches on (notifications, clipboard offers), made once she is loaded. */
    private NodeTools nodeTools;
    private java.util.concurrent.ScheduledExecutorService stagingTimer;
    /** The turn of hers about the parts that her reaction to is waited for, null for none. */
    private volatile String partsTurn;
    private RendererServer renderer;
    private LookingGlassWindow lookingGlass;
    /** Voice in (push-to-talk) of the assistant shown, null before one is loaded. */
    private VoiceInput voice;
    /** The pause that ends what the user says, when they have not chosen one (DESIGN.md → Voice). */
    private static final int DEFAULT_PAUSE_MS = 1200;

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
        // not launched (the screenshot mode starts the app itself): no arguments, the environment decides
        Parameters parameters = getParameters();
        settings = NodeSettings.standard(parameters == null ? new String[0] : parameters.getRaw().toArray(String[]::new));
        stage.setTitle("Joi");
        frame.setTop(editMenu());
        frame.setCenter(new StackPane());
        stage.setScene(new Scene(frame, 1100, 700));
        // the Looking Glass window would keep the app going: closing this one quits
        stage.setOnHidden(e -> Platform.exit());
        stage.show();
        NodeSettings.Node node = settings.node();
        LocalMode local = LocalMode.parse(parameters == null ? new String[0] : parameters.getRaw().toArray(String[]::new), System.getenv());
        if (local != null) {
            // started by the server's desktop app on this machine: its socket is the way in, nothing to type
            showLocal(local);
            return;
        }
        showServer(node.server == null ? "http://localhost:8080" : node.server, null);
    }

    /**
     * The server on this machine, over its local socket: signs in without a key and loads her. While nobody has an account yet
     * (the first one is made in the web UI) or the server is still starting, it asks again every few seconds.
     */
    private void showLocal(LocalMode local) {
        Label status = new Label("Connecting to Joi on this computer...");
        status.setWrapText(true);
        Button browser = new Button("Open Joi in the browser");
        browser.setVisible(local.web() != null);
        browser.setManaged(local.web() != null);
        browser.setOnAction(e -> {
            if (local.web() != null) {
                getHostServices().showDocument(local.web());
            }
        });
        Button other = new Button("Another server...");
        AtomicBoolean leave = new AtomicBoolean();
        other.setOnAction(e -> {
            leave.set(true);
            showServer("http://localhost:8080", null);
        });
        show(new StackPane(centered(new Label("Joi"), status, new HBox(8, browser, other))));
        Thread.ofVirtual().name("Joi node local").start(() -> {
            while (!leave.get()) {
                try {
                    NodeSettings.Node node = settings.node();
                    node.server = NodeSettings.normalise(local.server());
                    settings.saveNode();
                    ServerClient signedIn = new ServerClient(node.server, settings);
                    signedIn.signInLocal();
                    signedIn.info();
                    if (leave.get()) {
                        return;
                    }
                    Platform.runLater(() -> {
                        client = signedIn;
                        loadJois();
                    });
                    return;
                } catch (ServerClient.ServerException e) {
                    String text = "no_user".equals(e.code())
                            ? "Joi has no account yet. Open Joi in the browser and make the first one; this window goes on by itself."
                            : "Joi on this computer refused: " + e.getMessage();
                    Platform.runLater(() -> status.setText(text));
                } catch (IOException e) {
                    Platform.runLater(() -> status.setText("Joi does not answer on this computer yet (" + e.getMessage() + "). Is it running?"));
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @Override
    public void stop() {
        if (stagingTimer != null) {
            stagingTimer.shutdownNow();
        }
        if (socket != null) {
            socket.close();
        }
        if (browserLink != null) {
            browserLink.close();
        }
        if (voice != null) {
            voice.close();
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
    private MenuBar editMenu() {
        MenuItem cut = editItem("Cut", KeyCode.X, TextInputControl::cut);
        MenuItem copy = editItem("Copy", KeyCode.C, TextInputControl::copy);
        MenuItem paste = editItem("Paste", KeyCode.V, TextInputControl::paste);
        MenuItem all = editItem("Select All", KeyCode.A, TextInputControl::selectAll);
        MenuBar bar = new MenuBar(new Menu("Edit", null, cut, copy, paste, new SeparatorMenuItem(), all), new Menu("Joi", null, approvals, notifyMenu, clipMenu, harnessItem, browserItem));
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
                if (client.local()) {
                    client.signInLocal();
                }
                return client.info();
            }
        };
        task.setOnSucceeded(e -> {
            ServerInfo info = task.getValue();
            try {
                List<AuthMethod> methods = info.auth().methods();
                if (client.local() || settings.credentials().authorization() != null || methods.contains(AuthMethod.NONE)) {
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
    private record Loaded(JoiSummary summary, JoiInfo joi, com.github.enerccio.joi.protocol.http.ChatHistory history, BodyInfo body, Map<String, String> animations,
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
                com.github.enerccio.joi.protocol.http.ChatHistory history;
                try {
                    history = client.history(chosen.id(), HISTORY);
                } catch (ServerClient.ServerException e) {
                    // a server before 1.3: the chat starts empty
                    System.err.println("No chat history: " + e.getMessage());
                    history = new com.github.enerccio.joi.protocol.http.ChatHistory(List.of(), false);
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

    /**
     * Asks the user about a task of hers that waits (the server has no standing approval of its tool): approve, approve the
     * tool from now on (kept by the server), deny, or leave it (it can still be answered in the Joi dialog). The answer goes
     * through the server's HTTP API; the dialog is taken back when the server says it was answered elsewhere.
     */
    private void askAboutTask(String joi, TaskInfo task, ChatPane chat) {
        ButtonType approve = new ButtonType("Approve", ButtonBar.ButtonData.YES);
        ButtonType always = new ButtonType("Approve all of this tool", ButtonBar.ButtonData.APPLY);
        ButtonType deny = new ButtonType("Deny", ButtonBar.ButtonData.NO);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("She asks to use " + task.tool());
        alert.setHeaderText(task.title());
        alert.setContentText((task.toolDescription() == null ? "" : task.toolDescription() + "\n")
                + (task.url() == null ? "" : task.url() + "\n") + (task.question() == null ? "" : task.question()));
        alert.getButtonTypes().setAll(approve, always, deny, later);
        taskAlerts.put(task.id(), alert);
        alert.setOnHidden(e -> taskAlerts.remove(task.id()));
        alert.resultProperty().addListener((o, before, result) -> {
            if (result == null || result == later) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    if (result == deny) {
                        client.denyTask(joi, task.id(), null);
                    } else {
                        client.approveTask(joi, task.id(), result == always);
                    }
                } catch (ServerClient.ServerException e) {
                    if (e.status() != 409) {
                        Platform.runLater(() -> chat.note("The answer was not taken: " + e.getMessage()));
                    }
                } catch (IOException | InterruptedException e) {
                    Platform.runLater(() -> chat.note("The answer was not taken: " + e.getMessage()));
                }
                taskQuestions.answered(task.id());
            }, "Joi task answer");
            thread.setDaemon(true);
            thread.start();
        });
        alert.show();
    }

    /**
     * The tools the user approved ahead of time ("approve all of this tool", kept by the server): each can be taken back, and
     * its tasks ask again.
     */
    private void showApprovals(String joi, ChatPane chat) {
        VBox rows = new VBox(8);
        rows.setPadding(new Insets(8));
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Auto-approved tools");
        dialog.setHeaderText("Tasks of these tools run without asking.");
        dialog.getDialogPane().setContent(rows);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            Thread thread = new Thread(() -> {
                try {
                    AutoApprovalList list = client.autoApproved(joi);
                    Platform.runLater(() -> {
                        rows.getChildren().clear();
                        if (list.tools().isEmpty()) {
                            rows.getChildren().add(new Label("No tool is approved ahead of time."));
                        }
                        for (AutoApproval approved : list.tools()) {
                            Label name = new Label(approved.tool() + (approved.description() == null ? "" : " - " + approved.description()));
                            name.setWrapText(true);
                            Button ask = new Button("Ask again");
                            ask.setOnAction(e -> {
                                ask.setDisable(true);
                                Thread off = new Thread(() -> {
                                    try {
                                        client.autoApprove(joi, approved.tool(), false);
                                    } catch (IOException | InterruptedException ex) {
                                        Platform.runLater(() -> chat.note("Could not take the approval back: " + ex.getMessage()));
                                    }
                                    Platform.runLater(refresh[0]);
                                }, "Joi approvals");
                                off.setDaemon(true);
                                off.start();
                            });
                            HBox row = new HBox(12, name, ask);
                            row.setAlignment(Pos.CENTER_LEFT);
                            HBox.setHgrow(name, Priority.ALWAYS);
                            name.setMaxWidth(Double.MAX_VALUE);
                            rows.getChildren().add(row);
                        }
                    });
                } catch (IOException | InterruptedException e) {
                    Platform.runLater(() -> {
                        rows.getChildren().setAll(new Label("Could not read them: " + e.getMessage()));
                    });
                }
            }, "Joi approvals");
            thread.setDaemon(true);
            thread.start();
        };
        refresh[0].run();
        dialog.show();
    }

    /** Asks the user whether she may show a notification; blocks the calling thread (the tool's own) until they answer, or time runs out. */
    private boolean askNotification(String title, String text, long seconds) throws InterruptedException {
        java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
        Alert[] shown = new Alert[1];
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
            alert.setTitle("She wants to notify you");
            alert.setHeaderText(title);
            alert.resultProperty().addListener((o, before, result) -> answer.complete(result == ButtonType.YES));
            alert.setOnHidden(e -> answer.complete(false));
            shown[0] = alert;
            alert.show();
        });
        try {
            return answer.get(seconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Platform.runLater(() -> {
                if (shown[0] != null) {
                    shown[0].close();
                }
            });
            return false;
        }
    }

    /** Shows the user a text she offers for the clipboard and asks whether it is copied; no answer in time is a no. */
    private boolean askClipboard(String title, String text, long seconds) throws InterruptedException {
        java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
        Alert[] shown = new Alert[1];
        Platform.runLater(() -> {
            ButtonType copy = new ButtonType("Copy", ButtonBar.ButtonData.YES);
            ButtonType refuse = new ButtonType("Don't copy", ButtonBar.ButtonData.NO);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, copy, refuse);
            alert.setTitle("She offers you a text");
            alert.setHeaderText(title + " (" + text.length() + " characters): put it on your clipboard?");
            TextArea area = new TextArea(ClipboardOffers.preview(text, 20_000));
            area.setEditable(false);
            area.setWrapText(false);
            area.setPrefColumnCount(70);
            area.setPrefRowCount(16);
            alert.getDialogPane().setContent(area);
            alert.resultProperty().addListener((o, before, result) -> answer.complete(result == copy));
            alert.setOnHidden(e -> answer.complete(false));
            shown[0] = alert;
            alert.show();
        });
        try {
            return answer.get(seconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Platform.runLater(() -> {
                if (shown[0] != null) {
                    shown[0].close();
                }
            });
            return false;
        }
    }

    /** The system clipboard, set on the JavaFX thread. */
    private static void copyToClipboard(String text) throws IOException {
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        try {
            done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("The clipboard did not take it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped");
        }
    }

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
        approvals.setOnAction(e -> showApprovals(loaded.summary().id(), chat));
        approvals.setDisable(false);
        Notifications notifications = new Notifications(settings, new DesktopScreen(), (title, text, seconds) -> askNotification(title, text, seconds));
        notifyOff.setSelected(notifications.mode() == Notifications.Mode.OFF);
        notifyAsk.setSelected(notifications.mode() == Notifications.Mode.ASK);
        notifyAllow.setSelected(notifications.mode() == Notifications.Mode.ALLOW);
        java.util.Map<RadioMenuItem, Notifications.Mode> modes = java.util.Map.of(notifyOff, Notifications.Mode.OFF,
                notifyAsk, Notifications.Mode.ASK, notifyAllow, Notifications.Mode.ALLOW);
        modes.forEach((item, mode) -> item.setOnAction(e -> {
            try {
                notifications.setMode(mode);
            } catch (IOException ex) {
                chat.note("The choice was not kept: " + ex.getMessage());
            }
        }));
        notifyTest.setOnAction(e -> {
            try {
                notifications.test();
            } catch (IOException ex) {
                chat.note("The notification could not be shown: " + ex.getMessage());
            }
        });
        notifyMenu.setDisable(false);
        ClipboardOffers clipboardOffers = new ClipboardOffers(settings, JoiNodeApp::copyToClipboard, (title, text, seconds) -> askClipboard(title, text, seconds));
        if (browserLink == null) {
            // the browser companion: once per run, whichever assistant is loaded
            browserLink = new BrowserLink(settings.dir());
            try {
                browserLink.start();
            } catch (IOException e) {
                chat.note("The browser companion is not available: " + e.getMessage());
            }
        }
        Harness harness = new Harness(settings.dir(), (question, seconds) -> HarnessDialogs.ask(stage, question, seconds), "click", browserLink);
        this.harness = harness;
        // view_image: a picture goes to the server through the chat's upload, for this assistant
        ServerClient uploads = client;
        String joiId = loaded.summary().id();
        harness.imageUploader(file -> uploads.uploadImage(joiId, file).id());
        java.util.List<com.github.enerccio.joi.node.NodeTool> tools = new java.util.ArrayList<>(java.util.List.of(notifications, clipboardOffers));
        tools.addAll(harness.tools());
        nodeTools = new NodeTools(tools);
        harnessItem.setOnAction(e -> HarnessDialogs.settings(stage, harness, chat::note));
        harnessItem.setDisable(false);
        browserItem.setOnAction(e -> HarnessDialogs.browser(stage, harness, settings.dir(), chat::note));
        browserItem.setDisable(false);
        clipOff.setSelected(clipboardOffers.mode() == ClipboardOffers.Mode.OFF);
        clipAsk.setSelected(clipboardOffers.mode() == ClipboardOffers.Mode.ASK);
        clipAllow.setSelected(clipboardOffers.mode() == ClipboardOffers.Mode.ALLOW);
        java.util.Map.of(clipOff, ClipboardOffers.Mode.OFF, clipAsk, ClipboardOffers.Mode.ASK, clipAllow, ClipboardOffers.Mode.ALLOW).forEach((item, mode) -> item.setOnAction(e -> {
            try {
                clipboardOffers.setMode(mode);
            } catch (IOException ex) {
                chat.note("The choice was not kept: " + ex.getMessage());
            }
        }));
        clipMenu.setDisable(false);
        // the pictures of the chat come from the server's cache of them; earlier messages slide the window back
        chat.setImages(client::image);
        chat.setOnEarlier(before -> client.history(joiId, HISTORY, before));
        chat.history(loaded.history());
        SpeechPlayer speech = new SpeechPlayer();
        // her mouth follows her voice: the page reads each piece and is told when it plays
        speech.setOnQueued((number, chunk) -> avatarPane.speech(number, chunk.data()));
        speech.setOnStarted(avatarPane::speechStarted);
        speech.setOnStopped(avatarPane::speechStopped);
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
        controls.outfits(loaded.body() == null ? null : loaded.body().outfits());
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
        // the animation she holds, as the server last said; the director plays it when her body has it
        java.util.concurrent.atomic.AtomicReference<String> stance = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Runnable> resync = new java.util.concurrent.atomic.AtomicReference<>(() -> {
        });
        java.util.concurrent.atomic.AtomicBoolean resyncLater = new java.util.concurrent.atomic.AtomicBoolean();
        // which body and files are shown now: when the server's differ, her body is replaced, not only its parts switched
        java.util.concurrent.atomic.AtomicReference<String> shownBody = new java.util.concurrent.atomic.AtomicReference<>(bodyKey(loaded.body()));
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
                    String key = bodyKey(info);
                    if (!key.equals(shownBody.get())) {
                        // she is in another body (or her model or animations were written again): its files come, and it replaces hers
                        Path joim = info.model() == null || info.model().joim() == null ? null : client.download(info.model().joim(), bytes -> {
                        });
                        Path joia = info.animations() == null || info.animations().joia() == null ? null
                                : client.download(info.animations().joia(), bytes -> {
                                });
                        BodyFiles files = BodyFiles.unpack(joim, joia, settings.cache().resolve("unpacked"));
                        Map<String, String> names = new HashMap<>();
                        if (info.animations() != null) {
                            for (BodyAnimation animation : info.animations().animations()) {
                                names.put(animation.id(), animation.name());
                            }
                        }
                        Platform.runLater(() -> {
                            shownBody.set(key);
                            renderer.show(files);
                            avatarPane.names(names);
                            // her stance, when this body has it
                            director.stance(avatarPane.has(stance.get()) ? stance.get() : null);
                            parts.clear();
                            kept.clear();
                            for (BodyPart part : info.parts()) {
                                parts.put(part.id(), part);
                                kept.put(part.id(), part.state());
                            }
                            controls.parts(info.parts());
                            controls.outfits(info.outfits());
                            toggles.clear();
                            toggles.addAll(info.toggles() == null ? List.of() : info.toggles());
                            renderer.toggles(List.copyOf(toggles));
                            chat.note("She is in " + (info.name() == null ? "another body" : info.name()) + " now.");
                        });
                        return;
                    }
                    Platform.runLater(() -> {
                        for (BodyPart part : info.parts()) {
                            if (!java.util.Objects.equals(kept.get(part.id()), part.state())) {
                                keepPart(parts, kept, toggles, controls,
                                        new com.github.enerccio.joi.protocol.node.PartSwitch(part.id(), part.state()));
                            }
                        }
                        // an outfit (or another node's change) sets toggles that are in no part too: every toggle as the body has it
                        if (info.toggles() != null) {
                            toggles.clear();
                            toggles.addAll(info.toggles());
                            renderer.toggles(List.copyOf(toggles));
                        }
                        controls.outfits(info.outfits());
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> chat.note("Her body could not be read again: " + e.getMessage()));
                }
            });
        });
        controls.setOnOutfit(outfit -> {
            if (!socket.outfit(outfit.id())) {
                controls.outfits(loaded.body() == null ? null : loaded.body().outfits());
            }
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
                Harness loadedHarness = harness;
                if (loadedHarness != null) {
                    loadedHarness.turnStarted(turn, cause);
                }
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
            public void mood(com.github.enerccio.joi.protocol.node.MoodState mood) {
                Platform.runLater(() -> avatarPane.mood(mood));
            }

            @Override
            public void expression(com.github.enerccio.joi.protocol.node.ShowExpression shown) {
                Platform.runLater(() -> avatarPane.expression(shown));
            }

            @Override
            public void animation(com.github.enerccio.joi.protocol.node.PlayAnimation play) {
                Platform.runLater(() -> director.gesture(play.animation(), play.loops()));
            }

            @Override
            public void stance(com.github.enerccio.joi.protocol.node.StanceState held) {
                Platform.runLater(() -> {
                    stance.set(held.animation());
                    director.stance(avatarPane.has(held.animation()) ? held.animation() : null);
                });
            }

            @Override
            public void log(com.github.enerccio.joi.protocol.http.ChatLog note) {
                Platform.runLater(() -> chat.note(note.text()));
            }

            @Override
            public void task(TaskInfo task) {
                TaskInfo asked = taskQuestions.take(task);
                if (asked != null) {
                    Platform.runLater(() -> askAboutTask(loaded.summary().id(), asked, chat));
                } else if (!"waiting".equals(task.status())) {
                    // answered somewhere else: the question is taken back
                    Platform.runLater(() -> {
                        Alert open = taskAlerts.get(task.id());
                        if (open != null) {
                            open.close();
                        }
                    });
                }
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
        chat.setCanSeeImages(Boolean.TRUE.equals(loaded.joi().images()));
        chat.setOnSayImages((text, files) -> {
            // the images go to the server first (streamed from the disk), then the words with their ids
            Thread.ofVirtual().name("joi-image-upload").start(() -> {
                try {
                    List<String> ids = new ArrayList<>();
                    for (java.nio.file.Path file : files) {
                        ids.add(client.uploadImage(loaded.summary().id(), file).id());
                    }
                    socket.say(text, true, false, false, ids);
                } catch (ServerClient.ServerException e) {
                    Platform.runLater(() -> chat.note("The image was not sent: " + e.getMessage()));
                } catch (IOException | InterruptedException e) {
                    Platform.runLater(() -> chat.note("The image was not sent: " + e.getMessage()));
                }
            });
        });
        chat.setOnSayWithParts(text -> socket.say(text, true, false, true));
        Runnable stopHer = () -> {
            // talked over: her words and her voice stop
            socket.stop(null);
            speech.stop();
            director.speechEnded();
        };
        chat.setOnStop(stopHer);
        wireVoice(loaded.summary().name(), chat, controls, stopHer);

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
                        "joi-node (JavaFX)", loaded.summary().id(), List.of(NodePart.AUDIO_OUT, NodePart.AVATAR, NodePart.TOOLS), null, null);
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
                socket.localSocket(client.localSocket());
                socket.authorizer(client::authorization);
                socket.connect(client.nodeSocket(), client.authorization(), hello, manifest).get();
                Platform.runLater(() -> {
                    director.start();
                    online.accept(true);
                });
                return null;
            }
        });
    }

    /** Tells one body with its files from another: the body, the hash of its model and the hash of its animations. */
    private static String bodyKey(BodyInfo body) {
        if (body == null) {
            return "";
        }
        return body.id() + "|" + (body.model() == null || body.model().joim() == null ? "" : body.model().joim().sha256()) + "|"
                + (body.animations() == null || body.animations().joia() == null ? "" : body.animations().joia().sha256());
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

    /**
     * Voice in, push-to-talk: the Talk button and its key, the Voice section of the controls (the download of the speech model
     * and the pause). The speech is turned into text on this computer and said to her like typed words; talking over her
     * stops her, as the Stop button does.
     */
    private void wireVoice(String herName, ChatPane chat, ControlsPane controls, Runnable stopHer) {
        VoiceModels voiceModels = new VoiceModels(settings.dir());
        SpeechModel speechModel = SpeechModel.MOONSHINE_TINY_EN;
        if (voice != null) {
            voice.close();
        }
        VoiceInput voiceInput = VoiceInput.of(voiceModels, speechModel, () -> {
            Integer ms = nodeSetting(node -> node.voicePauseMs);
            return ms == null ? DEFAULT_PAUSE_MS : ms;
        });
        voice = voiceInput;
        javafx.scene.Scene scene = stage.getScene();
        javafx.scene.input.KeyCodeCombination escape = new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.ESCAPE);
        // Esc gives up on what is being said, only while the node listens (it is not taken from anything else)
        Runnable endListening = () -> {
            scene.getAccelerators().remove(escape);
            chat.talking(VoiceInput.State.IDLE);
        };
        VoiceInput.Listener listener = new VoiceInput.Listener() {
            @Override
            public void listening() {
                Platform.runLater(() -> {
                    chat.talking(VoiceInput.State.LISTENING);
                    scene.getAccelerators().put(escape, voiceInput::cancel);
                });
            }

            @Override
            public void transcribing() {
                Platform.runLater(() -> chat.talking(VoiceInput.State.TRANSCRIBING));
            }

            @Override
            public void heard(String text) {
                Platform.runLater(() -> {
                    endListening.run();
                    chat.heard(text);
                });
            }

            @Override
            public void nothing(String why) {
                Platform.runLater(() -> {
                    endListening.run();
                    chat.note(why);
                });
            }

            @Override
            public void failed(String message) {
                Platform.runLater(() -> {
                    endListening.run();
                    chat.note(message);
                });
            }
        };
        Runnable talk = () -> {
            switch (voiceInput.state()) {
                case LISTENING -> voiceInput.finish();
                case TRANSCRIBING -> {
                }
                case IDLE -> {
                    if (!voiceModels.installed(speechModel)) {
                        chat.note("To talk to " + herName + " this node needs a speech model (about 30 MB): download it under Voice in the controls.");
                        return;
                    }
                    if (!socket.connected()) {
                        chat.note("Not connected to the server: nothing was started.");
                        return;
                    }
                    // she stops when she is talked over, then the node listens
                    stopHer.run();
                    if (voiceInput.start(listener)) {
                        chat.talking(VoiceInput.State.LISTENING);
                    }
                }
            }
        };
        chat.setOnTalk(talk);
        scene.getAccelerators().put(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.SPACE,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN, javafx.scene.input.KeyCombination.SHIFT_DOWN), talk);

        Integer saved = nodeSetting(node -> node.voicePauseMs);
        VoicePanel panel = new VoicePanel(speechModel.label() + ", about " + Math.round(speechModel.size() / 1_000_000.0) + " MB",
                saved == null ? DEFAULT_PAUSE_MS : saved, ms -> {
            try {
                settings.node().voicePauseMs = ms;
                settings.saveNode();
            } catch (IOException e) {
                chat.note("The pause was not saved: " + e.getMessage());
            }
        });
        panel.installed(voiceModels.installed(speechModel));
        panel.setOnDownload(() -> {
            panel.progress(0);
            Thread.ofVirtual().name("joi-voice-download").start(() -> {
                try {
                    voiceModels.install(speechModel, done -> Platform.runLater(() -> panel.progress(done)));
                    Platform.runLater(() -> {
                        panel.installed(true);
                        chat.note("The speech model is ready: you can talk to " + herName + ".");
                    });
                    voiceInput.preload();
                } catch (IOException e) {
                    Platform.runLater(() -> panel.failed(e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        controls.section("Voice", panel);
        if (voiceModels.installed(speechModel)) {
            // loaded now, so that the first time the user talks does not wait for it
            Thread.ofVirtual().name("joi-voice-load").start(voiceInput::preload);
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
        /** Choosing an image to send with the words, or pasting one (Ctrl/Cmd+V with an image on the clipboard). */
        private final Button attach = new Button("Image…");
        private final HBox pendingBox = new HBox(8);
        private final List<java.nio.file.Path> pendingImages = new ArrayList<>();
        private boolean online = true;
        private boolean canSeeImages = true;
        private java.util.function.BiConsumer<String, List<java.nio.file.Path>> onSayImages = (text, files) -> {
        };
        private final Button stop = new Button("Stop");
        /** Talking to her (push-to-talk): a click starts listening, another sends what was said; it also ends at a pause. */
        private final Button talk = new Button("Talk");
        private Runnable onTalk = () -> {
        };
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
            lines.heightProperty().addListener((o, a, b) -> {
                if (!keepScroll) {
                    scroll.setVvalue(1);
                }
            });
            input.setPromptText("Talk to " + name);
            input.setOnAction(e -> send());
            send.setOnAction(e -> send());
            stop.setOnAction(e -> onStop.run());
            talk.setOnAction(e -> onTalk.run());
            talking(VoiceInput.State.IDLE);
            attach.setOnAction(e -> chooseImage());
            // a picture on the clipboard is attached instead of pasted (text still pastes as text)
            input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if ((e.isShortcutDown() && e.getCode() == KeyCode.V) && canSeeImages) {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    if (clipboard.hasImage() && !clipboard.hasString()) {
                        pasteImage(clipboard.getImage());
                        e.consume();
                    }
                }
            });
            pendingBox.setPadding(new Insets(6, 8, 0, 8));
            pendingBox.setVisible(false);
            pendingBox.setManaged(false);
            // two rows: the words and Send, then Talk, the image and Stop (one row of all of them did not fit the narrow chat)
            for (Button button : List.of(talk, attach, send, stop)) {
                button.setMinWidth(Region.USE_PREF_SIZE);
            }
            HBox typing = new HBox(8, input, send);
            HBox.setHgrow(input, Priority.ALWAYS);
            VBox bottom = new VBox(6, typing, new HBox(8, talk, attach, stop));
            bottom.setPadding(new Insets(8));
            offline.setPadding(new Insets(6, 8, 0, 8));
            offline.setAlignment(Pos.CENTER_LEFT);
            offline.setVisible(false);
            offline.setManaged(false);
            setCenter(scroll);
            setBottom(new VBox(offline, pendingBox, bottom));
        }

        /** Whether she can look at images (her info says): the attach button is there only then. */
        void setCanSeeImages(boolean canSee) {
            this.canSeeImages = canSee;
            attach.setDisable(!canSee || !online);
            attach.setTooltip(new Tooltip(canSee ? "Send an image with your words" : name + " cannot look at images: her chat model does not take them"));
        }

        void setOnSayImages(java.util.function.BiConsumer<String, List<java.nio.file.Path>> onSayImages) {
            this.onSayImages = onSayImages;
        }

        private void chooseImage() {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Choose an image");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
            java.io.File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (file != null) {
                addImage(file.toPath());
            }
        }

        /** A picture from the clipboard is written to a temp file (PNG) and waits like a chosen one. */
        private void pasteImage(javafx.scene.image.Image image) {
            try {
                java.nio.file.Path file = java.nio.file.Files.createTempFile("joi-paste", ".png");
                file.toFile().deleteOnExit();
                javax.imageio.ImageIO.write(buffered(image), "png", file.toFile());
                addImage(file);
            } catch (IOException e) {
                note("The picture on the clipboard was not taken: " + e.getMessage());
            }
        }

        /** The picture as an AWT image, to write it as PNG (JavaFX has no writer of its own, and the swing bridge is not a dependency). */
        private static java.awt.image.BufferedImage buffered(javafx.scene.image.Image image) {
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            return out;
        }

        private void addImage(java.nio.file.Path file) {
            if (pendingImages.size() >= 4) {
                note("At most 4 images go with a message.");
                return;
            }
            pendingImages.add(file);
            showPending();
        }

        private void showPending() {
            pendingBox.getChildren().clear();
            for (java.nio.file.Path file : List.copyOf(pendingImages)) {
                javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(file.toUri().toString(), 0, 48, true, true, true));
                Hyperlink remove = new Hyperlink("✕");
                remove.setOnAction(e -> {
                    pendingImages.remove(file);
                    showPending();
                });
                pendingBox.getChildren().add(new HBox(2, view, remove));
            }
            pendingBox.setVisible(!pendingImages.isEmpty());
            pendingBox.setManaged(!pendingImages.isEmpty());
        }

        /** Offline she can't be talked to (nor stopped): what is typed stays where it is until the connection is back. */
        void setOnline(boolean online) {
            this.online = online;
            attach.setDisable(!online || !canSeeImages);
            input.setDisable(!online);
            send.setDisable(!online);
            stop.setDisable(!online);
            talk.setDisable(!online);
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

        /** What the Talk button does: starts listening, or sends what was said so far. */
        void setOnTalk(Runnable onTalk) {
            this.onTalk = onTalk;
        }

        /** Shows what the voice input is doing on the Talk button. */
        void talking(VoiceInput.State state) {
            switch (state) {
                case IDLE -> {
                    talk.setText("Talk");
                    talk.setDisable(!online);
                    talk.setTooltip(new Tooltip("Talk to " + name + " (" + VoicePanel.shortcutName() + "+Shift+Space): she stops when you pause"));
                }
                case LISTENING -> {
                    talk.setText("Listening… send");
                    talk.setDisable(false);
                    talk.setTooltip(new Tooltip("Click to send what you said now (Esc: never mind)"));
                }
                case TRANSCRIBING -> {
                    talk.setText("Writing it down…");
                    talk.setDisable(true);
                }
            }
        }

        /**
         * What the user said by voice: it goes as if it was typed, with the images that wait to be sent (those attached
         * while they were talking too). What is typed in the field stays there.
         */
        void heard(String text) {
            if (!pendingImages.isEmpty()) {
                sendWithImages(text);
            } else {
                add("You: " + text);
                onSay.accept(text);
            }
        }

        private void send() {
            String text = input.getText().strip();
            if (!pendingImages.isEmpty()) {
                input.clear();
                sendWithImages(text);
            } else if (!text.isEmpty()) {
                input.clear();
                add("You: " + text);
                onSay.accept(text);
            }
        }

        /** The words with the images that wait: they go together, and nothing waits after. */
        private void sendWithImages(String text) {
            List<java.nio.file.Path> files = List.copyOf(pendingImages);
            pendingImages.clear();
            showPending();
            add("You: " + (text.isEmpty() ? "" : text + " ") + "[" + files.size() + (files.size() == 1 ? " image]" : " images]"));
            HBox thumbs = new HBox(4);
            for (java.nio.file.Path file : files) {
                thumbs.getChildren().add(new javafx.scene.image.ImageView(new javafx.scene.image.Image(file.toUri().toString(), 0, 96, true, true, true)));
            }
            lines.getChildren().add(thumbs);
            onSayImages.accept(text, files);
        }

        /** Fetches an image of the chat by its id (the server's, cached); null shows none. Called off the FX thread. */
        interface ImageSource {
            java.nio.file.Path image(String id) throws Exception;
        }

        /** The page of her history before a message (its id). Called off the FX thread. */
        interface EarlierSource {
            com.github.enerccio.joi.protocol.http.ChatHistory before(String id) throws Exception;
        }

        private ImageSource images;
        private EarlierSource earlier;
        /** Her history as loaded, oldest first: the top of the chat, drawn again when earlier pages come. */
        private final List<ChatEntry> past = new ArrayList<>();
        private final VBox pastBox = new VBox(8);
        private final Hyperlink showEarlier = new Hyperlink("Show earlier messages");
        private boolean more;
        /** Set while earlier messages are put on top: the chat stays where the user is, it does not jump to the end. */
        private boolean keepScroll;

        void setImages(ImageSource images) {
            this.images = images;
        }

        void setOnEarlier(EarlierSource earlier) {
            this.earlier = earlier;
        }

        /** Her history from the server: the chat goes on where it was. */
        void history(com.github.enerccio.joi.protocol.http.ChatHistory page) {
            past.clear();
            past.addAll(page.messages());
            more = page.more();
            // the words field keeps the focus: a space typed into the window must not load a page
            showEarlier.setFocusTraversable(false);
            showEarlier.setOnAction(e -> loadEarlier());
            if (!lines.getChildren().contains(pastBox)) {
                lines.getChildren().addFirst(pastBox);
            }
            drawPast();
        }

        /** The page before the first message shown, put on top. */
        private void loadEarlier() {
            if (earlier == null || past.isEmpty()) {
                return;
            }
            String first = past.getFirst().id();
            showEarlier.setDisable(true);
            Thread.ofVirtual().start(() -> {
                try {
                    com.github.enerccio.joi.protocol.http.ChatHistory page = earlier.before(first);
                    javafx.application.Platform.runLater(() -> {
                        double before = lines.getHeight();
                        double at = scroll.getVvalue() * Math.max(0, before - scroll.getViewportBounds().getHeight());
                        past.addAll(0, page.messages());
                        more = page.more();
                        keepScroll = true;
                        drawPast();
                        // keep the message that was on top where it was: scroll down by what was added
                        lines.applyCss();
                        lines.layout();
                        double after = lines.getHeight();
                        double room = Math.max(1, after - scroll.getViewportBounds().getHeight());
                        scroll.setVvalue(Math.min(1, (at + after - before) / room));
                        keepScroll = false;
                        showEarlier.setDisable(false);
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        showEarlier.setDisable(false);
                        note("Earlier messages could not be loaded: " + ex.getMessage());
                    });
                }
            });
        }

        /**
         * Draws the history: "Show earlier" on top while there is more, the exchanges with their pictures, and a line after the
         * last one a summary covers (above it she no longer gets the messages, only the summary).
         */
        private void drawPast() {
            pastBox.getChildren().clear();
            if (more) {
                pastBox.getChildren().add(showEarlier);
            }
            int lastSummarized = -1;
            for (int i = 0; i < past.size(); i++) {
                if (past.get(i).isSummarized()) {
                    lastSummarized = i;
                }
            }
            for (int i = 0; i < past.size(); i++) {
                ChatEntry entry = past.get(i);
                if (entry.text() != null || (entry.images() != null && !entry.images().isEmpty())) {
                    pastBox.getChildren().add(line("You: " + (entry.text() == null ? "" : entry.text())
                            + (entry.images() == null || entry.images().isEmpty() ? "" : " [" + entry.images().size() + (entry.images().size() == 1 ? " image]" : " images]"))));
                    thumbnails(pastBox, entry.images());
                } else if (entry.text() == null && entry.event() != null && entry.reply() != null) {
                    // an event she answered: the kind, so her reply is not out of nowhere
                    Label kind = line("(" + entry.event() + ")");
                    kind.setStyle("-fx-opacity: 0.6;");
                    pastBox.getChildren().add(kind);
                }
                if (entry.reply() != null) {
                    pastBox.getChildren().add(line(name + ": " + entry.reply()));
                }
                thumbnails(pastBox, entry.toolImages());
                if (i == lastSummarized) {
                    pastBox.getChildren().add(summaryBreak());
                }
            }
        }

        /** The line under the summarized messages: she no longer gets them, only the summary of them. */
        private Node summaryBreak() {
            javafx.scene.control.Separator left = new javafx.scene.control.Separator();
            javafx.scene.control.Separator right = new javafx.scene.control.Separator();
            Label label = new Label("Summarized above: no longer sent to " + name);
            label.setStyle("-fx-font-size: 0.85em; -fx-opacity: 0.75;");
            label.setMinWidth(Region.USE_PREF_SIZE);
            HBox.setHgrow(left, Priority.ALWAYS);
            HBox.setHgrow(right, Priority.ALWAYS);
            HBox line = new HBox(8, left, label, right);
            line.setAlignment(Pos.CENTER);
            line.setPadding(new Insets(4, 0, 4, 0));
            line.setStyle("-fx-border-color: transparent;");
            line.setId("summary-break");
            return line;
        }

        /** The pictures of a message, small, each fetched in the background; a click opens it whole. */
        private void thumbnails(VBox into, List<String> ids) {
            if (ids == null || ids.isEmpty() || images == null) {
                return;
            }
            HBox row = new HBox(4);
            into.getChildren().add(row);
            for (String id : ids) {
                Thread.ofVirtual().start(() -> {
                    try {
                        java.nio.file.Path file = images.image(id);
                        if (file == null) {
                            return;
                        }
                        String uri = file.toUri().toString();
                        javafx.application.Platform.runLater(() -> {
                            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(uri, 0, 96, true, true, true));
                            view.setCursor(javafx.scene.Cursor.HAND);
                            view.setOnMouseClicked(e -> showWhole(uri));
                            row.getChildren().add(view);
                        });
                    } catch (Exception e) {
                        System.err.println("An image of the chat was not loaded: " + e.getMessage());
                    }
                });
            }
        }

        private void showWhole(String uri) {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(uri, true));
            view.setPreserveRatio(true);
            view.setFitWidth(1000);
            view.setFitHeight(800);
            javafx.stage.Stage window = new javafx.stage.Stage();
            window.setTitle(name);
            window.setScene(new javafx.scene.Scene(new ScrollPane(view)));
            if (getScene() != null) {
                window.initOwner(getScene().getWindow());
            }
            window.show();
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
            Label line = line(text);
            lines.getChildren().add(line);
            return line;
        }

        /** A line of the chat; its menu copies it, or the whole conversation, to the clipboard (a label cannot be selected). */
        private Label line(String text) {
            Label line = new Label(text);
            line.setWrapText(true);
            javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("Copy");
            copy.setOnAction(e -> toClipboard(line.getText()));
            javafx.scene.control.MenuItem copyAll = new javafx.scene.control.MenuItem("Copy the conversation");
            copyAll.setOnAction(e -> toClipboard(transcript()));
            line.setContextMenu(new javafx.scene.control.ContextMenu(copy, copyAll));
            return line;
        }

        /** The words of the chat as shown, one line each. */
        String transcript() {
            StringBuilder out = new StringBuilder();
            collect(lines, out);
            return out.toString().strip();
        }

        private static void collect(javafx.scene.Parent parent, StringBuilder out) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof Label label) {
                    out.append(label.getText()).append('\n');
                } else if ("summary-break".equals(child.getId())) {
                    out.append("---\n");
                } else if (child instanceof VBox box) {
                    collect(box, out);
                }
            }
        }

        private static void toClipboard(String text) {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        }
    }
}
