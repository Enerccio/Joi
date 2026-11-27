package com.github.enerccio.joi.node.fx;

import com.github.enerccio.joi.node.NodeTools;
import com.github.enerccio.joi.node.harness.Harness;
import com.github.enerccio.joi.node.harness.Policy;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** The JavaFX side of the harness: the question about a call (Allow once, Allow for session, Deny) and the settings (Joi → Files and shell…). */
final class HarnessDialogs {

    private HarnessDialogs() {
    }

    /** Asks the user about a call; null when they did not answer in time (or the call was stopped: the dialog closes then). */
    static NodeTools.Scope ask(Window owner, Harness.Question question, long seconds) throws InterruptedException {
        CompletableFuture<NodeTools.Scope> answer = new CompletableFuture<>();
        Alert[] shown = new Alert[1];
        Platform.runLater(() -> {
            ButtonType once = new ButtonType("Allow once", ButtonBar.ButtonData.YES);
            ButtonType session = new ButtonType("Allow for session", ButtonBar.ButtonData.OTHER);
            ButtonType deny = new ButtonType("Deny", ButtonBar.ButtonData.NO);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, once, session, deny);
            if (owner != null) {
                alert.initOwner(owner);
            }
            alert.setTitle(question.title());
            alert.setHeaderText(question.title() + (question.warning() == null ? "" : "\n" + question.warning()));
            TextArea area = new TextArea(question.target() + (question.detail() == null ? "" : "\n\n" + question.detail()));
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefColumnCount(70);
            area.setPrefRowCount(question.detail() == null ? 4 : 14);
            Label note = new Label("Allow for session allows the same again until the node quits. No answer in "
                    + seconds / 60 + " minutes is a no.");
            note.setWrapText(true);
            alert.getDialogPane().setContent(new VBox(8, area, note));
            alert.resultProperty().addListener((o, before, result) -> answer.complete(
                    result == once ? NodeTools.Scope.ONCE : result == session ? NodeTools.Scope.SESSION : NodeTools.Scope.DENIED));
            alert.setOnHidden(e -> answer.complete(NodeTools.Scope.DENIED));
            shown[0] = alert;
            alert.show();
        });
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return NodeTools.Scope.DENIED;
        } catch (TimeoutException e) {
            return null;
        } finally {
            Platform.runLater(() -> {
                if (shown[0] != null && shown[0].isShowing()) {
                    shown[0].close();
                }
            });
        }
    }

    /** Joi → Files and shell…: the two switches and the user's folders; each change is kept at once (and she gets a new manifest). */
    static void settings(Window owner, Harness harness, Consumer<String> note) {
        Policy policy = harness.policy();
        CheckBox files = new CheckBox("Files: she may read and list files (silent in your folders, asked elsewhere) and write them (always asked)");
        files.setWrapText(true);
        files.setSelected(policy.filesOn());
        CheckBox shell = new CheckBox("Shell: she may run commands, each one asked");
        shell.setWrapText(true);
        shell.setSelected(policy.shellOn());
        ListView<String> roots = new ListView<>();
        roots.getItems().setAll(policy.roots());
        roots.setPrefHeight(140);
        Button add = new Button("Add a folder…");
        Button remove = new Button("Remove");
        remove.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        Label where = new Label("The policy is " + harness.policyFile() + " (also edited by hand; credentials, browser profiles and the "
                + "system's folders are kept from her there). Every call is logged in " + harness.auditFile() + ".");
        where.setWrapText(true);
        files.setOnAction(e -> keep(note, () -> harness.setFiles(files.isSelected())));
        shell.setOnAction(e -> keep(note, () -> harness.setShell(shell.isSelected())));
        add.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("A folder of yours she may read without asking");
            File chosen = chooser.showDialog(owner);
            if (chosen != null) {
                keep(note, () -> harness.addRoot(chosen.getAbsolutePath()));
                roots.getItems().setAll(harness.policy().roots());
            }
        });
        remove.setOnAction(e -> {
            String selected = roots.getSelectionModel().getSelectedItem();
            if (selected != null) {
                keep(note, () -> harness.removeRoot(selected));
                roots.getItems().setAll(harness.policy().roots());
            }
        });
        VBox content = new VBox(10, files, shell, new Label("Your folders (reads and lists inside them are not asked about):"), roots,
                new HBox(8, add, remove), where);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        Alert alert = new Alert(Alert.AlertType.NONE, null, ButtonType.CLOSE);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Files and shell");
        alert.setHeaderText("What she may do on this machine");
        alert.getDialogPane().setContent(content);
        alert.show();
    }

    /** Joi → Browser…: the switch, Connect Firefox, and whether the extension is connected. */
    static void browser(Window owner, Harness harness, java.nio.file.Path nodeHome, Consumer<String> note) {
        CheckBox on = new CheckBox("She may use Firefox on this machine: see what plays and control it, see and switch tabs, open pages "
                + "(each as your policy says)");
        on.setWrapText(true);
        on.setSelected(harness.policy().browserOn());
        Label state = new Label();
        state.setWrapText(true);
        Runnable show = () -> state.setText(harness.browser() != null && harness.browser().connected()
                ? "Firefox is connected."
                : com.github.enerccio.joi.node.browser.BrowserHost.installed(nodeHome)
                ? "Firefox can reach this node, but the Joi extension is not connected: install it, or restart Firefox."
                : "Firefox cannot reach this node yet: Connect Firefox.");
        show.run();
        Button connect = new Button("Connect Firefox");
        connect.setOnAction(e -> {
            try {
                com.github.enerccio.joi.node.browser.BrowserHost.Installed installed = com.github.enerccio.joi.node.browser.BrowserHost.install(nodeHome);
                note.accept("Firefox can reach this node now (" + installed.manifest() + ").");
            } catch (IOException ex) {
                note.accept("Firefox could not be connected: " + ex.getMessage());
            }
            show.run();
        });
        on.setOnAction(e -> keep(note, () -> harness.setBrowser(on.isSelected())));
        Label sites = new Label("The extension works only on the sites you allow from its button in Firefox. Mail, password managers and "
                + "payments are refused whatever you allow (deniedSites in " + harness.policyFile() + ", with each tool's tier in browserTools).");
        sites.setWrapText(true);
        VBox content = new VBox(10, on, new HBox(8, connect), state, sites);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        Alert alert = new Alert(Alert.AlertType.NONE, null, ButtonType.CLOSE);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Browser");
        alert.setHeaderText("Joi in Firefox");
        alert.getDialogPane().setContent(content);
        alert.show();
    }

    private interface Change {
        void run() throws IOException;
    }

    private static void keep(Consumer<String> note, Change change) {
        try {
            change.run();
        } catch (IOException e) {
            note.accept("The choice was not kept: " + e.getMessage());
        }
    }
}
