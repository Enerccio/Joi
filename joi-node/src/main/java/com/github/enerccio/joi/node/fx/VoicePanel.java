package com.github.enerccio.joi.node.fx;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

/**
 * The Voice section of the controls: whether the node can listen (the speech model is on the disk) and the button that
 * downloads it, which says what is downloaded, how much and from where, and the pause that ends what the user says.
 */
final class VoicePanel extends VBox {

    private final Label status = new Label();
    private final Button download = new Button();
    private final ProgressBar bar = new ProgressBar(0);
    private final Spinner<Integer> pause;
    private Runnable onDownload = () -> {
    };

    /**
     * @param what      the model's label and size, said before it is downloaded ("English (Moonshine tiny), about 30 MB")
     * @param pauseMs   the pause now, in milliseconds
     * @param onPause   told the pause the user chose
     */
    VoicePanel(String what, int pauseMs, IntConsumer onPause) {
        super(6);
        status.setWrapText(true);
        download.setText("Download the speech model");
        download.setOnAction(e -> onDownload.run());
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.setManaged(false);
        Label hint = new Label("Talk with the button below the chat, or with Shift+" + shortcutName() + "+Space. Speech is turned into text here, "
                + "on this computer; only the words go to the server. Download: " + what + ", from the sherpa-onnx project on GitHub, "
                + "kept in this node's folder.");
        hint.setWrapText(true);
        hint.setStyle("-fx-opacity: 0.6;");
        pause = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(500, 4000, pauseMs, 100));
        pause.setEditable(false);
        pause.setPrefWidth(90);
        pause.valueProperty().addListener((o, before, now) -> {
            if (now != null) {
                onPause.accept(now);
            }
        });
        Label pauseLabel = new Label("Pause that ends what I say (ms)");
        pauseLabel.setWrapText(true);
        HBox pauseRow = new HBox(8, pauseLabel, pause);
        pauseRow.setPadding(new Insets(2, 0, 0, 0));
        getChildren().addAll(status, download, bar, pauseRow, hint);
    }

    /** What the Command (macOS) or Ctrl key is called here. */
    static String shortcutName() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? "Cmd" : "Ctrl";
    }

    void setOnDownload(Runnable onDownload) {
        this.onDownload = onDownload;
    }

    /** The model is on the disk (or not): the button is for downloading it. */
    void installed(boolean installed) {
        status.setText(installed ? "Speech to text is ready." : "Speech to text needs a model that is not on this computer yet.");
        download.setVisible(!installed);
        download.setManaged(!installed);
        download.setDisable(false);
        bar.setVisible(false);
        bar.setManaged(false);
    }

    /** The download runs: how far it is, 0 to 1. */
    void progress(double done) {
        download.setDisable(true);
        bar.setVisible(true);
        bar.setManaged(true);
        bar.setProgress(done);
        status.setText("Downloading the speech model… " + Math.round(done * 100) + " %");
    }

    /** The download failed: why, and the button is there again. */
    void failed(String message) {
        installed(false);
        status.setText("The download failed: " + message);
    }
}
